# item-storage-service — Architecture

## Стек
| Компонент | Технология |
|-----------|-----------|
| Runtime | Python 3.12 |
| Transport | gRPC (grpcio 1.78) + gRPC Reflection |
| БД | PostgreSQL 15 (asyncpg + SQLAlchemy 2.0 async) |
| Объектное хранилище | Amazon S3 (или S3-совместимое: MinIO для dev) |
| S3 клиент | aioboto3 (async) |
| Шифрование | AES-256-GCM (server-managed key) |
| Миграции | Alembic |
| Порт | `50056` |

---

## Зона ответственности

Item-storage-service отвечает за хранение пользовательских медиа-ресурсов уровня профиля и настроек, доступ к которым должны иметь все участники системы (например, аватар видит любой пользователь, ringtone слышит только владелец). В отличие от `media-service`, здесь:

- **Шифрование управляется сервером** (AES-256-GCM, server-managed key). Клиент загружает открытые данные по presigned URL, сервис шифрует объект при сохранении в S3 через SSE-KMS (или SSE-S3 для MinIO).
- **Контроль доступа реализован внутри сервиса** — каждый запрос несёт `requester_user_id`, и сервис сам проверяет допустимость выдачи ресурса.
- **Доступ из оркестратора напрямую** — сервис является публичным (в рамках внутренней сети) и вызывается оркестратором, а не другим микросервисом.

**Что хранит сервис:**

| Категория | Примеры |
|-----------|---------|
| Аватары | `avatar` (публичный), `group_avatar` (публичный) |
| Медиа профиля | `profile_background` (публичный) |
| Медиа настроек | `chat_background` (приватный, только владелец) |
| Аудио | `notification_sound`, `ringtone` (приватный, только владелец) |

**Что сервис НЕ делает:**
- Не хранит E2E-зашифрованные сообщения или вложения чатов — это зона `media-service`
- Не выдаёт данные без проверки прав `requester_user_id`
- Не управляет профилем пользователя — только бинарными ресурсами; метаданные (`avatar_media_id`, `ringtone_media_id` и т.д.) хранятся в `user-service`; `group_avatar` ссылка хранится в `messaging-service` (поле `conversations.avatar_media_id`)

---

## Модель доступа

| Тип ресурса | Кто может скачать |
|-------------|-------------------|
| `avatar` | Любой аутентифицированный пользователь |
| `group_avatar` | Любой аутентифицированный пользователь |
| `profile_background` | Любой аутентифицированный пользователь |
| `chat_background` | Только владелец (`owner_user_id = requester_user_id`) |
| `notification_sound` | Только владелец |
| `ringtone` | Только владелец |

Сервис опирается на поле `access_policy` (`public` / `owner_only`) в таблице `storage_items` для принятия решения без обращения к другим сервисам.

> **Приватность профиля не проверяется здесь.** Если у пользователя `profile_visible_to = nobody`, оркестратор или `user-service` не должны выдавать `avatar_media_id` стороннему запрашивающему. Item-storage-service предполагает, что вызывающий уже прошёл эту проверку — он лишь проверяет, что ресурс с данным ID существует и policy разрешает выдачу.

---

## Соглашение по именованию S3 ключей

```
items/{owner_user_id}/{item_type}/{item_id}
```

Пример: `items/550e8400-e29b-41d4-a716.../avatar/7c9e6679...`

Это позволяет удалять все ресурсы пользователя по префиксу `items/{user_id}/` при soft-delete аккаунта.

---

## Шифрование

Используется **SSE-KMS** (AWS KMS) в prod или **SSE-S3** для MinIO/dev. Ключ управляется инфраструктурой, не хранится в коде. Клиент получает и загружает данные в открытом виде через presigned URL — шифрование прозрачно применяется на уровне S3 при записи и снимается при чтении.

> В отличие от `media-service` где шифрование E2E на клиенте и сервер слепой, здесь сервер **видит содержимое** при генерации presigned URL и верификации. Это необходимо — аватар должен отображаться у всех участников без дополнительных ключей.

---

## База данных (PostgreSQL)

### `storage_items`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | ID ресурса; совпадает с `*_media_id` в `user-service` |
| `owner_user_id` | UUID NOT NULL | Владелец ресурса |
| `item_type` | VARCHAR(50) NOT NULL | `avatar` / `profile_background` / `chat_background` / `notification_sound` / `ringtone` |
| `s3_bucket` | VARCHAR(255) NOT NULL | Имя бакета |
| `s3_key` | TEXT UNIQUE NOT NULL | Полный ключ объекта в S3 |
| `mime_type` | VARCHAR(100) NOT NULL | `image/jpeg`, `image/png`, `audio/ogg`, `audio/mpeg`, … |
| `size_bytes` | BIGINT NOT NULL | Размер файла в байтах |
| `access_policy` | VARCHAR(20) NOT NULL | `public` / `owner_only` |
| `status` | VARCHAR(20) NOT NULL | `pending` / `uploaded` / `deleted` |
| `created_at` | TIMESTAMP NOT NULL | — |
| `uploaded_at` | TIMESTAMP NULLABLE | Время подтверждения загрузки |
| `deleted_at` | TIMESTAMP NULLABLE | Время удаления из S3 |

**Индексы:**
- INDEX(`owner_user_id`, `item_type`) — поиск активных ресурсов пользователя по типу
- INDEX(`status`) WHERE `status = 'pending'` — для cleanup задачи просроченных pending

### Ограничения по типу и размеру

| `item_type` | Допустимые MIME | Максимальный размер |
|-------------|-----------------|---------------------|
| `avatar` | `image/jpeg`, `image/png`, `image/webp` | 5 МБ |
| `group_avatar` | `image/jpeg`, `image/png`, `image/webp` | 5 МБ |
| `profile_background` | `image/jpeg`, `image/png`, `image/webp` | 10 МБ |
| `chat_background` | `image/jpeg`, `image/png`, `image/webp` | 10 МБ |
| `notification_sound` | `audio/ogg`, `audio/mpeg`, `audio/aac` | 1 МБ |
| `ringtone` | `audio/ogg`, `audio/mpeg`, `audio/aac` | 5 МБ |

---

## gRPC API

### `InitiateUpload(InitiateUploadRequest) → InitiateUploadResponse`
Начало загрузки ресурса. Возвращает presigned PUT URL для прямой загрузки в S3.

**Кто вызывает:** Оркестратор (от лица аутентифицированного пользователя).

**Вход:**
- `owner_user_id: UUID`
- `item_type: string` — одно из допустимых значений
- `mime_type: string`
- `size_bytes: int64`

**Логика:**
1. Валидация `item_type`, `mime_type` и `size_bytes` по таблице ограничений → `INVALID_ARGUMENT`
2. Формирование `s3_key = items/{owner_user_id}/{item_type}/{item_id}`
3. Определение `access_policy` по `item_type` (статическое правило)
4. INSERT `storage_items` (status=`pending`)
5. `s3.generate_presigned_url('put_object', ..., ServerSideEncryption='aws:kms')`

**Возврат:**
- `item_id: UUID`
- `upload_url: string` — presigned S3 PUT URL (TTL из конфига, default 3600s)
- `expires_at: int64` (Unix timestamp)

---

### `ConfirmUpload(ConfirmUploadRequest) → ConfirmUploadResponse`
Подтверждение успешной загрузки в S3. Вызывается клиентом после PUT по presigned URL.

**Кто вызывает:** Оркестратор.

**Вход:**
- `owner_user_id: UUID`
- `item_id: UUID`

**Логика:**
1. Проверка `owner_user_id = storage_items.owner_user_id` → `PERMISSION_DENIED`
2. Проверка `status = 'pending'` → `NOT_FOUND`
3. `s3.head_object(Bucket, Key)` — проверка факта загрузки и сверка размера (допуск ±1%)
4. UPDATE `status = 'uploaded'`, `uploaded_at = now()`

**Возврат:** `success: bool`

**Ошибки:** `NOT_FOUND` — объект не найден в S3; `FAILED_PRECONDITION` — размер не совпадает

---

### `GetDownloadUrl(GetDownloadUrlRequest) → GetDownloadUrlResponse`
Получение временной ссылки для скачивания ресурса.

**Кто вызывает:** Оркестратор (при рендере профиля, загрузке настроек и т.п.).

**Вход:**
- `item_id: UUID`
- `requester_user_id: UUID`

**Логика:**
1. SELECT `storage_items` WHERE `id = item_id` AND `status = 'uploaded'` → `NOT_FOUND`
2. Проверка `access_policy`:
   - `public` → разрешено любому аутентифицированному `requester_user_id`
   - `owner_only` → `requester_user_id ≠ owner_user_id` → `PERMISSION_DENIED`
3. `s3.generate_presigned_url('get_object', ..., ExpiresIn=900)`

**Возврат:**
- `download_url: string`
- `expires_at: int64`
- `mime_type: string`

---

### `DeleteItem(DeleteItemRequest) → DeleteItemResponse`
Удаление ресурса из S3 и пометка в БД. Вызывается при смене аватара, удалении аккаунта и т.п.

**Кто вызывает:** Оркестратор или `user-service` (internal).

**Вход:**
- `owner_user_id: UUID`
- `item_id: UUID`

**Логика:**
1. Проверка `owner_user_id = storage_items.owner_user_id` → `PERMISSION_DENIED`
2. `s3.delete_object(Bucket, Key)`
3. UPDATE `status = 'deleted'`, `deleted_at = now()`

**Возврат:** `success: bool`

---

### `DeleteUserItems(DeleteUserItemsRequest) → DeleteUserItemsResponse`
Массовое удаление всех ресурсов пользователя. Вызывается при удалении аккаунта.

**Кто вызывает:** Оркестратор при обработке `DeleteUser`.

**Вход:**
- `owner_user_id: UUID`
- `item_types?: repeated string` — если пусто, удаляются все типы

**Логика:**
1. SELECT всех `uploaded`-записей пользователя (по фильтру `item_types` если передан)
2. `s3.delete_objects(Bucket, Delete={ Objects: [{Key}, …] })` — батчами по 1000
3. Batch UPDATE `status = 'deleted'`

**Возврат:** `deleted_count: int`

---

### `CleanupPendingUploads(CleanupPendingUploadsRequest) → CleanupPendingUploadsResponse`
Удаление записей в статусе `pending`, которые не были подтверждены в течение TTL. Вызывается по расписанию (cron / scheduler в оркестраторе).

**Вход:** `older_than_seconds: int` (default: 7200 — 2 часа), `batch_size: int` (default: 100)

**Логика:**
1. SELECT WHERE `status = 'pending'` AND `created_at < now() - older_than_seconds`
2. Попытка `s3.delete_object` для каждого (игнорируется `NoSuchKey`)
3. UPDATE `status = 'deleted'`

**Возврат:** `cleaned_count: int`

> ⚠️ До добавления scheduler в оркестраторе метод не будет вызываться автоматически.

---

### `GetItemMetadata(GetItemMetadataRequest) → GetItemMetadataResponse`
Получение метаданных ресурса без генерации URL. Используется для проверки существования и свежести.

**Кто вызывает:** Оркестратор, `user-service` (internal).

**Вход:** `item_id: UUID`, `requester_user_id: UUID`

**Логика:** Та же проверка `access_policy`, без вызова S3.

**Возврат:** `item_id`, `owner_user_id`, `item_type`, `mime_type`, `size_bytes`, `uploaded_at`, `access_policy`

---

### `HealthCheck(HealthCheckRequest) → HealthCheckResponse`
Проверяет PostgreSQL (`SELECT 1`) и S3 (HeadBucket).

**Возврат:** `status` (`ok`/`degraded`), `db_status`, `s3_status`, `version`

---

## Зависимости

```
item-storage-service
├── PostgreSQL  (storage_items)
└── S3          (Amazon S3 / MinIO для dev)
```

Нет зависимостей от других микросервисов. Авторизацию на уровне «можно ли вообще видеть профиль этого пользователя» делегирует вызывающей стороне (оркестратор / `user-service`). Внутри сервиса проверяется только `access_policy` ресурса.

---

## Конфигурация (.env)

```env
GRPC_PORT=50056
DATABASE_URL=postgresql+asyncpg://storage_user:storage_password@localhost:5432/item_storage_db
AWS_ACCESS_KEY_ID=...
AWS_SECRET_ACCESS_KEY=...
AWS_REGION=eu-central-1
S3_BUCKET_NAME=messenger-items-prod
S3_ENDPOINT_URL=           # Пусто для AWS; для MinIO: http://minio:9000
S3_SSE_TYPE=aws:kms        # или AES256 для MinIO/dev
KMS_KEY_ID=                # ARN ключа KMS; пусто если SSE-S3
PRESIGNED_UPLOAD_TTL=3600
PRESIGNED_DOWNLOAD_TTL=900
PENDING_CLEANUP_AFTER_SECONDS=7200
ENVIRONMENT=development
```

---

## Отличия от media-service

| Аспект | media-service | item-storage-service |
|--------|---------------|----------------------|
| Шифрование | E2E на клиенте (сервер слепой) | Server-managed (SSE-KMS/SSE-S3) |
| Контроль доступа | Делегирован messaging-service | Встроен (access_policy) |
| Вызывается из | messaging-service | Оркестратор напрямую |
| Контент | Вложения сообщений | Ресурсы профиля и настроек |
| Видимость контента | Сервер не видит plaintext | Сервер контролирует доступ к plaintext |
| Публичность | Нет публичных ресурсов | Часть ресурсов публична (`avatar`) |