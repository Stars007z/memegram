# media-service — Architecture

## Стек
| Компонент | Технология |
|-----------|-----------|
| Runtime | Python 3.12 |
| Transport | gRPC (grpcio 1.78) + gRPC Reflection |
| БД | PostgreSQL 15 (asyncpg + SQLAlchemy 2.0 async) |
| Объектное хранилище | Amazon S3 (или S3-совместимое: MinIO для dev) |
| S3 клиент | aioboto3 (async) |
| Миграции | Alembic |
| Порт | `50055` |

> Redis не нужен на старте — все операции stateless (генерация presigned URL).

---

## Зона ответственности

Media-service — тонкий прокси между messaging-service и S3. Он:
- Генерирует presigned URL для PUT (upload) и GET (download)
- Верифицирует существование объекта в S3
- Управляет метаданными объектов (размер, MIME, статус)
- Удаляет объекты (при soft-delete сообщений, по TTL)

**Что он НЕ делает:**
- Не знает содержимого файлов — всё зашифровано на клиенте до загрузки
- Не проверяет права доступа пользователя — это ответственность messaging-service
- Не хранит ключи шифрования — `encryption_metadata` хранится в messaging-service

---

## Соглашение по именованию S3 ключей

```
media/{conversation_id}/{media_id}/{mime_prefix}
```

Пример: `media/550e8400-e29b-41d4-a716/7c9e6679.../image`

Это обеспечивает логическую группировку по чатам и позволяет удалять все медиа чата одним `delete_objects` по префиксу.

---

## База данных (PostgreSQL)

### `media_objects`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | Совпадает с `media_attachments.id` в messaging-service |
| `s3_bucket` | VARCHAR(255) NOT NULL | Имя бакета |
| `s3_key` | TEXT UNIQUE NOT NULL | Полный ключ объекта в S3 |
| `mime_type` | VARCHAR(100) NOT NULL | `image/jpeg`, `video/mp4`, `audio/ogg`, `application/octet-stream`, … |
| `encrypted_size` | BIGINT NOT NULL | Размер зашифрованного файла в байтах |
| `status` | VARCHAR(20) NOT NULL | `pending` / `uploaded` / `deleted` |
| `created_at` | TIMESTAMP NOT NULL | — |
| `uploaded_at` | TIMESTAMP NULLABLE | Время подтверждения загрузки |
| `deleted_at` | TIMESTAMP NULLABLE | Время удаления из S3 |
| `expires_at` | TIMESTAMP NULLABLE | NULL = без TTL; NOT NULL = автоудаление по расписанию |

**Индексы:** INDEX(`status`, `expires_at`) WHERE `status = 'uploaded' AND expires_at IS NOT NULL`

> Таблица нужна для: отслеживания статуса загрузки, повторных presigned URL при потере первого, аудита, TTL-удаления.

---

## gRPC API

### `GetUploadPresignedUrl(GetUploadPresignedUrlRequest) → GetUploadPresignedUrlResponse`
Генерирует presigned PUT URL для загрузки зашифрованного файла напрямую в S3.

**Кто вызывает:** messaging-service при `InitiateMediaUpload`.

**Вход:**
- `media_id: UUID` — ID, выданный messaging-service
- `conversation_id: UUID` — для формирования S3-ключа
- `mime_type: string`
- `encrypted_size: int64`
- `expires_in_seconds: int` (default: 3600)

**Логика:**
1. Формирование `s3_key = media/{conversation_id}/{media_id}/{mime_prefix}`
2. INSERT `media_objects` (status=`pending`)
3. `s3.generate_presigned_url('put_object', Bucket, Key, ExpiresIn, ContentType, ContentLength)`

**Возврат:**
- `upload_url: string` — presigned S3 PUT URL
- `s3_key: string` — сохранить в messaging-service.media_attachments
- `expires_at: int64` (Unix timestamp)

---

### `VerifyObjectExists(VerifyObjectExistsRequest) → VerifyObjectExistsResponse`
Проверяет что объект действительно загружен в S3. Вызывается при подтверждении загрузки.

**Кто вызывает:** messaging-service при `ConfirmMediaUpload`.

**Вход:** `media_id: UUID`, `s3_key: string`

**Логика:**
1. `s3.head_object(Bucket, Key)` — проверка существования и получение размера
2. Сверка `actual_size` с `encrypted_size` из `media_objects` (допуск ±1%)
3. UPDATE `media_objects.status = 'uploaded'`, `uploaded_at = now()`

**Возврат:** `exists: bool`, `actual_size: int64`

**Ошибки:** `NOT_FOUND` — объект не найден в S3; `FAILED_PRECONDITION` — размер не совпадает

---

### `GetDownloadPresignedUrl(GetDownloadPresignedUrlRequest) → GetDownloadPresignedUrlResponse`
Генерирует временную presigned GET URL для скачивания файла.

**Кто вызывает:** messaging-service при `GetMediaDownloadUrl`.

**Вход:**
- `s3_key: string`
- `expires_in_seconds: int` (default: 900 — 15 минут)

**Логика:**
1. Проверка `media_objects.status = 'uploaded'`
2. `s3.generate_presigned_url('get_object', Bucket, Key, ExpiresIn)`

**Возврат:** `download_url: string`, `expires_at: int64`

**Ошибки:** `NOT_FOUND` — объект не в статусе `uploaded`

---

### `DeleteObject(DeleteObjectRequest) → DeleteObjectResponse`
Удаление объекта из S3 и пометка в БД.

**Кто вызывает:** messaging-service при soft-delete сообщения с медиа или удалении чата.

**Вход:** `media_id: UUID`, `s3_key: string`

**Логика:**
1. `s3.delete_object(Bucket, Key)`
2. UPDATE `media_objects.status = 'deleted'`, `deleted_at = now()`

**Возврат:** `success: bool`

---

### `DeleteObjectsBatch(DeleteObjectsBatchRequest) → DeleteObjectsBatchResponse`
Массовое удаление объектов (например, при удалении всего чата).

**Кто вызывает:** messaging-service или оркестратор при удалении conversation.

**Вход:** `objects[] { media_id: UUID, s3_key: string }` (max 1000 за раз)

**Логика:**
1. `s3.delete_objects(Bucket, Delete={ Objects: [{Key}, …] })` — один API-вызов до 1000 объектов
2. Batch UPDATE `status = 'deleted'` в `media_objects`

**Возврат:** `deleted_count: int`, `failed[] { media_id, error }`

---

### `ProcessExpiredObjects(ProcessExpiredObjectsRequest) → ProcessExpiredObjectsResponse`
Удаление объектов с истёкшим TTL. Вызывается по расписанию (cron / scheduler в оркестраторе).

**Вход:** `batch_size: int` (default: 100)

**Логика:**
1. SELECT FROM `media_objects` WHERE `status = 'uploaded'` AND `expires_at < now()` LIMIT `batch_size`
2. Для каждого — `DeleteObject`

**Возврат:** `deleted_count: int`

> ⚠️ До добавления scheduler в оркестраторе метод не будет вызываться автоматически.

---

### `HealthCheck(HealthCheckRequest) → HealthCheckResponse`
Проверяет PostgreSQL (`SELECT 1`) и S3 (ListBuckets или HeadBucket).

**Возврат:** `status` (`ok`/`degraded`), `db_status`, `s3_status`, `version`

---

## Конфигурация (.env)

```env
GRPC_PORT=50055
DATABASE_URL=postgresql+asyncpg://media_user:media_password@localhost:5432/media_db
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_REGION=eu-central-1
S3_BUCKET_NAME=messenger-media-prod
S3_ENDPOINT_URL=          # Пусто для AWS; для MinIO: http://minio:9000
PRESIGNED_UPLOAD_TTL=3600
PRESIGNED_DOWNLOAD_TTL=900
MAX_UPLOAD_SIZE_BYTES=104857600   # 100 МБ
ENVIRONMENT=development
```

---

## Зависимости

```
media-service
├── PostgreSQL  (media_objects)
└── S3          (Amazon S3 / MinIO для dev)
```

> Нет зависимостей от других сервисов. media-service не вызывает auth/user/contacts/messaging.
> Авторизацию делегирует вызывающей стороне (messaging-service).
