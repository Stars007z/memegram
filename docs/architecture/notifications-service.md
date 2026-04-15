# notifications-service — Architecture

## Стек
| Компонент | Технология |
|-----------|-----------|
| Runtime | Python 3.12 |
| Transport (API) | gRPC (grpcio 1.78) + gRPC Reflection |
| Потребление событий | Redis Streams (consumer group) |
| БД | PostgreSQL 15 (asyncpg + SQLAlchemy 2.0 async) |
| Push: Android | FCM v1 API (firebase-admin 6.x) |
| Push: iOS | APNs HTTP/2 (aioapns 3.x) |
| Кеш / дедупликация | Redis 7 |
| Миграции | Alembic |
| Порт | `50057` |

---

## Зона ответственности

Notifications-service отвечает за доставку push-уведомлений на мобильные устройства. Сервис:

- **Потребляет события** из Redis Stream, публикуемые messaging-service
- **Формирует и отправляет push** через FCM (Android) и APNs (iOS)
- **Управляет push-токенами** устройств (регистрация, ротация, инвалидация)
- **Обеспечивает retry** при сбоях доставки с exponential backoff
- **Кеширует** метаданные (имена, аватары) для минимизации межсервисных вызовов

**Что сервис НЕ делает:**
- Не хранит и не видит содержимое сообщений (E2E-шифрование MLS — сервер слепой)
- Не управляет подписками / мутингом чатов (будущая фича на стороне messaging-service)
- Не отправляет email / SMS — только мобильный push
- Не управляет badge-счётчиком — это ответственность клиента на основе локального состояния

---

## Архитектурное решение: транспорт событий

| Критерий | gRPC (синхронный вызов) | Apache Kafka | Redis Streams |
|----------|------------------------|--------------|---------------|
| Связанность | Высокая — `SendMessage` ждёт ответа | Низкая | Низкая |
| Надёжность | Потеря при недоступности сервиса | Высокая (дисковая персистенция, репликация) | Средняя (ограничена памятью + RDB/AOF) |
| Новая инфраструктура | Нет | Да (Kafka + KRaft, схема-регистр) | Нет (Redis уже есть) |
| Consumer groups | N/A | Да | Да |
| Replay / redelivery | N/A | Да (по offset) | Да (по ID, XAUTOCLAIM) |
| Acknowledgement | N/A | Да (commit offset) | Да (XACK) |
| Влияние на латентность `SendMessage` | Увеличивает (synchronous) | Нет (async, `XADD` ≈ O(1)) | Нет (async, `XADD` ≈ O(1)) |
| Операционная сложность | Низкая | Высокая (брокеры, партиции, retention) | Низкая |
| Горизонтальное масштабирование | Load balancer | Партиции + consumer groups | Consumer groups |
| Путь миграции | — | — | → Kafka при необходимости |

**Решение для MVP: Redis Streams.**

Messaging-service при отправке сообщения / изменении состава группы выполняет `XADD` в стрим `notifications:events` на своём Redis (порт 6381). Notifications-service запускает consumer group `notifications-cg` и асинхронно обрабатывает события. При росте нагрузки Redis Streams заменяется на Kafka без изменения бизнес-логики — меняется только транспортный адаптер.

> ⚠️ Redis Streams персистентны в рамках Redis (RDB/AOF), но менее надёжны, чем Kafka при аварии узла. Для push-уведомлений это допустимо: потеря push не приводит к потере сообщения — клиент получит его при следующем открытии приложения через `GetMessages`.

---

## Архитектурное решение: содержимое push в E2E-мессенджере

Сервер **не имеет доступа к plaintext** сообщений (MLS-шифрование, `mls_ciphertext` — непрозрачный blob). Это определяет стратегию отображения push.

| Подход | Описание | Плюсы | Минусы |
|--------|----------|-------|--------|
| Metadata-only | Сервер отправляет: имя группы, тип сообщения, аватар. Текст: «Новое сообщение» | Просто, работает при убитом приложении | Пользователь не видит превью текста |
| Silent push + decrypt | Silent push, клиент просыпается, скачивает, расшифровывает, показывает локальное уведомление | Полное превью | Сложная клиентская часть, жёсткие ограничения iOS по фоновой работе |
| **Гибридный** | Visible push с метаданными + `mutable-content` (iOS) / data message (Android). Клиент при возможности дорасшифровывает | Баланс UX и простоты, graceful fallback | Средняя сложность клиента |

**Решение: гибридный подход.**

Сервер отправляет push с метаданными — имя группы / отправителя, аватар, тип сообщения, время. Клиент:
1. **iOS:** Notification Service Extension перехватывает push, загружает аватар, при возможности скачивает и расшифровывает сообщение (лимит ≈30 сек), обновляет текст уведомления
2. **Android:** FCM data message обрабатывается `FirebaseMessagingService`, аналогичная логика

**Fallback:** если клиент не успел расшифровать — показывается оригинальный текст: «Новое сообщение в [Название группы]».

### Что отображается в уведомлении

| Событие | Группа (group) | Личный чат (direct) |
|---------|----------------|---------------------|
| `new_message` | **Title:** Название группы | **Title:** Имя отправителя |
|               | **Body:** «Имя: Новое сообщение» / «Имя: Фото» / «Имя: Видео» | **Body:** «Новое сообщение» / «Фото» / «Видео» |
|               | **Avatar:** Аватарка группы | **Avatar:** Аватарка отправителя |
| `member_added` | **Title:** Название группы | — |
|                | **Body:** «Вас добавили в группу» | — |
| `member_kicked` | **Title:** Название группы | — |
|                 | **Body:** «Вас удалили из группы» | — |

> Маппинг `message_type` → человекочитаемый тип: `text` → «Новое сообщение», `image` → «Фото», `video` → «Видео», `audio` → «Голосовое сообщение», `file` → «Файл».

---

## Push-платформы: FCM и APNs

### FCM (Android)

Firebase Cloud Messaging v1 API. Используется **data message** (без ключа `notification`), чтобы приложение полностью контролировало отображение — в том числе в background и killed-состоянии.

**Payload `new_message`:**
```json
{
  "message": {
    "token": "<device_fcm_token>",
    "data": {
      "event_type": "new_message",
      "conversation_id": "uuid",
      "conversation_type": "group",
      "conversation_name": "Название группы",
      "sender_user_id": "uuid",
      "sender_name": "Имя",
      "message_type": "text",
      "avatar_url": "https://s3.../presigned",
      "timestamp": "2026-01-15T12:00:00Z"
    },
    "android": {
      "priority": "high",
      "ttl": "86400s"
    }
  }
}
```

| Параметр | Описание |
|----------|----------|
| `priority: high` | Пробуждает устройство из Doze mode. Для сообщений мессенджера — допустимо по политике Google |
| `ttl: 86400s` | Сообщение хранится на серверах FCM до 24ч, если устройство оффлайн |
| Data-only | Android не показывает уведомление автоматически; приложение создаёт `NotificationCompat.Builder` с `MessagingStyle`, загружает аватар через Glide/Coil |

### APNs (iOS)

Apple Push Notification service через HTTP/2 с JWT-аутентификацией (token-based, `.p8` ключ).

**Payload `new_message`:**
```json
{
  "aps": {
    "alert": {
      "title": "Название группы",
      "body": "Имя: Новое сообщение"
    },
    "mutable-content": 1,
    "sound": "default",
    "thread-id": "<conversation_id>"
  },
  "conversation_id": "uuid",
  "conversation_type": "group",
  "sender_user_id": "uuid",
  "sender_name": "Имя",
  "message_type": "text",
  "avatar_url": "https://s3.../presigned",
  "timestamp": "2026-01-15T12:00:00Z"
}
```

| Параметр | Описание |
|----------|----------|
| `mutable-content: 1` | Активирует Notification Service Extension — расширение может загрузить аватар, расшифровать сообщение, изменить title/body |
| `thread-id` | Группировка уведомлений по чатам в Notification Center (iOS сворачивает уведомления одного чата) |
| `sound: default` | Стандартный звук. В будущем — кастомный звук из `notification_sound` настроек пользователя (user-service) |

> **APNs push type:** `apns-push-type: alert`, `apns-priority: 10` (immediate delivery). Заголовок `apns-topic` = Bundle ID приложения.

### Notification Service Extension (iOS) — клиентская часть

Расширение (отдельный target в Xcode), запускается iOS при получении push с `mutable-content: 1`:

1. Извлекает `avatar_url` из payload → скачивает изображение → добавляет как `UNNotificationAttachment` (аватарка отображается в уведомлении)
2. *(Опционально, Phase 2)* Скачивает зашифрованное сообщение через API, расшифровывает MLS → обновляет `body` с реальным текстом
3. Вызывает `contentHandler(modifiedContent)` до таймаута (~30 сек)
4. При таймауте iOS показывает оригинальный push — «Новое сообщение в [Группа]»

> ⚠️ Extension работает в отдельном процессе с лимитом памяти ~24 МБ. MLS-расшифровка (OpenMLS) может не уложиться в этот бюджет. Поэтому для MVP — только загрузка аватара; расшифровка — Phase 2 после профилирования.

---

## База данных (PostgreSQL)

### `device_push_tokens`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `user_id` | UUID NOT NULL | Владелец устройства |
| `device_id` | UUID NOT NULL | ID из auth-service (`devices.id`) |
| `platform` | VARCHAR(10) NOT NULL | `ios` / `android` |
| `push_token` | TEXT NOT NULL | FCM registration token или APNs device token |
| `is_active` | BOOLEAN DEFAULT true | `false` = токен невалиден (unregistered / превышен лимит ошибок) |
| `created_at` | TIMESTAMP NOT NULL | — |
| `updated_at` | TIMESTAMP NOT NULL | Время последнего обновления токена |
| `last_success_at` | TIMESTAMP NULLABLE | Время последней успешной доставки через этот токен |
| `consecutive_failures` | INTEGER DEFAULT 0 | Счётчик последовательных ошибок доставки |

**Индексы:** UNIQUE(`device_id`), INDEX(`user_id`) WHERE `is_active = true`, INDEX(`push_token`)

> Push-токены обновляются клиентом при каждом запуске приложения — FCM и APNs могут ротировать токены. При `consecutive_failures >= 3` и ошибке типа «unregistered» → `is_active = false`.

> Одно устройство = один push-токен. Constraint `UNIQUE(device_id)` гарантирует это. При обновлении токена — UPSERT.

---

### `notification_log` *(опционально — для аналитики и дебага)*
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `event_type` | VARCHAR(50) NOT NULL | `new_message` / `member_added` / `member_kicked` |
| `conversation_id` | UUID NOT NULL | — |
| `recipient_user_id` | UUID NOT NULL | — |
| `device_id` | UUID NULLABLE | — |
| `platform` | VARCHAR(10) | `ios` / `android` |
| `status` | VARCHAR(20) NOT NULL | `sent` / `failed` / `dropped` |
| `error_code` | VARCHAR(100) NULLABLE | Код ошибки FCM/APNs |
| `attempts` | INTEGER DEFAULT 1 | Количество попыток |
| `created_at` | TIMESTAMP NOT NULL | — |

**Партиционирование:** `PARTITION BY RANGE (created_at)` — по неделям. Auto-cleanup партиций старше 30 дней.

> Для MVP можно ограничиться structured JSON application-логами (stdout → ELK/Loki) без этой таблицы. Включается при необходимости аналитики delivery rate.

---

## Потребляемые события (Redis Streams)

### Stream: `notifications:events` (на Redis messaging-service, порт 6381)

Messaging-service выполняет `XADD notifications:events * type <event_type> payload <json>` при событиях, требующих push.

Consumer group: `notifications-cg`
Consumer name: `notifications-{instance_id}` (для горизонтального масштабирования)

---

#### Событие `new_message`

Публикуется из `SendMessage` после успешного INSERT.

```json
{
  "type": "new_message",
  "conversation_id": "uuid",
  "conversation_type": "direct|group",
  "conversation_name": "Название группы",
  "avatar_media_id": "uuid|empty",
  "sender_user_id": "uuid",
  "message_type": "text|image|video|audio|file",
  "message_id": "uuid",
  "created_at": "2026-01-15T12:00:00Z"
}
```

> Для `direct`-чатов `conversation_name` пустое — клиент и так знает имя собеседника. Сервер использует `sender_name` из user-service.

---

#### Событие `member_added`

Публикуется из `CommitGroupChange` при наличии `added_user_ids`.

```json
{
  "type": "member_added",
  "conversation_id": "uuid",
  "conversation_name": "Название группы",
  "avatar_media_id": "uuid|empty",
  "added_user_ids": ["uuid1", "uuid2"],
  "added_by_user_id": "uuid"
}
```

---

#### Событие `member_kicked`

Публикуется из `KickMember` после успешного UPDATE.

```json
{
  "type": "member_kicked",
  "conversation_id": "uuid",
  "conversation_name": "Название группы",
  "kicked_user_id": "uuid",
  "kicked_by_user_id": "uuid"
}
```

> ⚠️ Событие `member_left` (пользователь **сам** вышел из группы) НЕ генерирует push — это его собственное действие.

---

## gRPC API

### `RegisterPushToken(RegisterPushTokenRequest) → RegisterPushTokenResponse`
Регистрация или обновление push-токена устройства. Вызывается клиентом при каждом запуске приложения.

**Кто вызывает:** Оркестратор (от лица аутентифицированного пользователя).

**Вход:**
- `user_id` UUID
- `device_id` UUID
- `platform` string — `ios` / `android`
- `push_token` string

**Логика:**
1. Валидация `platform` ∈ {`ios`, `android`} → `INVALID_ARGUMENT`
2. Валидация `push_token` — непустая строка → `INVALID_ARGUMENT`
3. UPSERT `device_push_tokens` ON CONFLICT (`device_id`):
   - UPDATE `push_token`, `platform`, `updated_at = now()`, `is_active = true`, `consecutive_failures = 0`
   - Если токен изменился — сброс `last_success_at`

**Возврат:** `success: bool`

---

### `UnregisterPushToken(UnregisterPushTokenRequest) → UnregisterPushTokenResponse`
Удаление push-токена. Вызывается при logout или отключении уведомлений пользователем.

**Кто вызывает:** Оркестратор.

**Вход:** `user_id`, `device_id`

**Логика:**
1. Проверка `user_id` = владелец записи → `PERMISSION_DENIED`
2. UPDATE `is_active = false` WHERE `device_id`

**Возврат:** `success: bool`

---

### `HealthCheck(HealthCheckRequest) → HealthCheckResponse`
Проверяет PostgreSQL (`SELECT 1`), Redis (PING), FCM (валидность credentials), APNs (валидность .p8 ключа).

**Возврат:** `status` (`ok`/`degraded`), `db_status`, `redis_status`, `fcm_status`, `apns_status`, `version`

---

## Логика обработки событий (Event Consumer)

### Общий pipeline

```
Redis Stream (XREADGROUP)
  → Десериализация события
  → Дедупликация (Redis SET)
  → Определение получателей
  → Обогащение метаданных (имена, аватары — из кеша или gRPC)
  → Формирование payload по платформе
  → Параллельная отправка (asyncio.gather)
  → Обработка ответов (retry / инвалидация)
  → XACK
```

---

### Обработка `new_message`

1. Дедупликация: `SETNX notif:dedup:{stream_id}` TTL=3600 → если ключ уже существует, `XACK` и пропуск
2. `messaging-service.GetConversationMembers(conversation_id)` → `member_user_ids[]` (кеш 30 сек)
3. Исключение `sender_user_id` из списка получателей
4. SELECT `device_push_tokens` WHERE `user_id IN (recipients)` AND `is_active = true`
5. Если нет активных токенов → `XACK`, лог `no_active_tokens`
6. `user-service.GetUsersBatch([sender_user_id])` → `sender_name`, `sender_avatar_media_id` (кеш 5 мин)
7. Определение `avatar_media_id`:
   - `group` → `conversation.avatar_media_id` (из события)
   - `direct` → `sender_avatar_media_id` (из user-service)
8. Если `avatar_media_id` не пустой → `item-storage-service.GetDownloadUrl(avatar_media_id)` → `avatar_url` (кеш 10 мин)
9. Формирование payload:
   - **iOS (APNs):** alert с `title` / `body` / `mutable-content: 1` / `thread-id` + custom keys
   - **Android (FCM):** data message с `priority: high` / `ttl: 86400s`
10. `asyncio.gather` — параллельная отправка на все устройства
11. Обработка результатов: retry transient / инвалидация permanent
12. `XACK`

---

### Обработка `member_added`

1. Дедупликация
2. SELECT `device_push_tokens` WHERE `user_id IN (added_user_ids)` AND `is_active = true`
3. Если `avatar_media_id` → `item-storage-service.GetDownloadUrl`
4. Push на каждое устройство:
   - **Title:** `{conversation_name}`
   - **Body:** «Вас добавили в группу»
5. `XACK`

---

### Обработка `member_kicked`

1. Дедупликация
2. SELECT `device_push_tokens` WHERE `user_id = kicked_user_id` AND `is_active = true`
3. Push:
   - **Title:** `{conversation_name}`
   - **Body:** «Вас удалили из группы»
4. `XACK`

---

## Retry-логика

### Уровни ошибок

| Тип ошибки | Примеры | Действие |
|------------|---------|----------|
| **Transient** | Таймаут, 5xx от FCM/APNs, сетевая ошибка | Retry с exponential backoff |
| **Permanent (токен)** | `UNREGISTERED` (FCM), `BadDeviceToken` (APNs) | Инвалидация токена, без retry |
| **Rate limit** | `429 Too Many Requests` | Retry после значения из `Retry-After` |

### Exponential backoff (per-device retry)

```
attempt 1:  задержка 1 сек
attempt 2:  задержка 2 сек
attempt 3:  задержка 4 сек
attempt 4:  задержка 8 сек
attempt 5:  задержка 16 сек (max)
```

Максимум **5 попыток** на одно устройство за одно событие. Jitter ±30% для предотвращения thundering herd. После исчерпания → лог + метрика `notification_permanently_failed`.

### Redis Streams retry (уровень события)

Если consumer падает посреди обработки (не успел `XACK`), сообщение остаётся в pending list:

- Отдельная корутина периодически (каждые 30 сек) выполняет `XAUTOCLAIM notifications-cg notifications-{id} 0 60000` — забирает сообщения, зависшие >60 сек
- Проверка `delivery_count` (встроенный счётчик Redis Streams):
  - `delivery_count <= 10` → повторная обработка
  - `delivery_count > 10` → `XACK` + лог `dead_letter` (событие потеряно, но сообщение всё равно доступно клиенту через `GetMessages`)

### Инвалидация токенов

| Платформа | Ошибка | Действие |
|-----------|--------|----------|
| FCM | `UNREGISTERED` | `is_active = false`, `consecutive_failures` не важен |
| FCM | `INVALID_ARGUMENT` (невалидный токен) | `is_active = false` |
| APNs | `BadDeviceToken` | `is_active = false` |
| APNs | `Unregistered` | `is_active = false` |
| APNs | `ExpiredProviderToken` | Ротация JWT-токена провайдера, retry |
| Обе | `consecutive_failures >= 3` (только transient) | `is_active = false` (перестраховка — вероятно приложение удалено) |

При успешной отправке: `consecutive_failures = 0`, `last_success_at = now()`.

---

## Redis-ключи (собственный Redis, порт 6382)

| Ключ | TTL | Назначение |
|------|-----|------------|
| `notif:dedup:{stream_message_id}` | 1 час | Дедупликация обработанных событий (защита от повторной доставки после XAUTOCLAIM) |
| `notif:avatar_url:{media_id}` | 10 мин | Кеш presigned URL аватарки (URL живёт 15 мин в item-storage-service) |
| `notif:sender:{user_id}` | 5 мин | Кеш `{ name, avatar_media_id }` отправителя |
| `notif:members:{conversation_id}` | 30 сек | Кеш списка активных членов чата |

---

## Зависимости

```
notifications-service
├── PostgreSQL            (device_push_tokens, notification_log)
├── Redis (own, 6382)     (кеш, дедупликация)
├── Redis (messaging, 6381)  (потребление стрима notifications:events)
├── messaging-service     (GetConversationMembers — internal gRPC, порт 50054)
├── user-service          (GetUsersBatch — internal gRPC, порт 50052)
├── item-storage-service  (GetDownloadUrl — для presigned URL аватарки, порт 50056)
├── FCM                   (Google Firebase Cloud Messaging — push Android)
└── APNs                  (Apple Push Notification service — push iOS)
```

---

## Необходимые изменения в messaging-service

Для публикации событий в Redis Stream messaging-service необходимо добавить `XADD` в следующих местах:

| Метод | Событие | Точка вставки |
|-------|---------|---------------|
| `SendMessage` | `new_message` | После INSERT `messages` + UPDATE `conversations.last_activity_at` |
| `CommitGroupChange` | `member_added` | После обработки `added_user_ids` (шаг 6), если список не пуст |
| `KickMember` | `member_kicked` | После UPDATE `conversation_members.left_at` (шаг 6) |

`XADD` выполняется **после** основной транзакции (не внутри неё), чтобы не блокировать основную логику при проблемах с Redis. При ошибке `XADD` — лог предупреждения, сообщение не теряется (доступно через `GetMessages`).

> Опционально: `MAXLEN ~ 100000` на стриме для ограничения памяти Redis (~100K событий ≈ 50–100 МБ).

---

## Необходимые изменения в оркестраторе

| Эндпоинт | Метод | Описание |
|----------|-------|----------|
| `/api/v1/notifications/push-token` | POST | → `RegisterPushToken` |
| `/api/v1/notifications/push-token` | DELETE | → `UnregisterPushToken` |

Клиент вызывает `POST /push-token` при каждом запуске приложения, передавая актуальный FCM/APNs-токен. `DELETE /push-token` — при logout (в дополнение к `auth.Logout`).

---

## Конфигурация (.env)

```env
GRPC_PORT=50057
DATABASE_URL=postgresql+asyncpg://notif_user:notif_password@localhost:5438/notifications_db

# Собственный Redis (кеш, дедупликация)
REDIS_URL=redis://localhost:6382/0

# Redis messaging-service (потребление стрима)
MESSAGING_REDIS_URL=redis://localhost:6381/0
STREAM_NAME=notifications:events
CONSUMER_GROUP=notifications-cg

# FCM (Android)
GOOGLE_APPLICATION_CREDENTIALS=/path/to/firebase-service-account.json
FCM_PROJECT_ID=memegram-prod

# APNs (iOS)
APNS_KEY_PATH=/path/to/AuthKey_XXXXXXXXXX.p8
APNS_KEY_ID=XXXXXXXXXX
APNS_TEAM_ID=YYYYYYYYYY
APNS_BUNDLE_ID=com.memegram.app
APNS_USE_SANDBOX=true   # false для production

# Retry
MAX_RETRY_ATTEMPTS=5
RETRY_BASE_DELAY_SEC=1
RETRY_JITTER_PERCENT=30
MAX_TOKEN_CONSECUTIVE_FAILURES=3
STREAM_DEAD_LETTER_THRESHOLD=10

# gRPC-зависимости
MESSAGING_GRPC_HOST=messaging-service
MESSAGING_GRPC_PORT=50054
USER_GRPC_HOST=user-service
USER_GRPC_PORT=50052
ITEM_STORAGE_GRPC_HOST=item-storage-service
ITEM_STORAGE_GRPC_PORT=50056

ENVIRONMENT=development
```
