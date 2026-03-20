# user-service — Architecture

## Стек
| Компонент | Технология |
|-----------|-----------|
| Runtime | Python 3.12 |
| Transport | gRPC (grpcio 1.78) + gRPC Reflection |
| БД | PostgreSQL 15 (asyncpg + SQLAlchemy 2.0 async) |
| Кеш / debounce | Redis 7 |
| Миграции | Alembic |
| Порт | `50052` |

---

## База данных

### `users`
| Колонка | Тип | Описание |
|---------|-----------------------------|----------|
| `id` | UUID PK | Совпадает с `user_id` из auth-service |
| `username` | VARCHAR NOT UNIQUE NOT NULL | — |
| `avatar_media_id` | UUID | — |
| `profile_background_media_id` | UUID | — |
| `user_public_key` | TEXT UNIQUE NOT NULL (base64) | Публичный ключ для поиска пользователя |
| `bio` | VARCHAR | — |
| `last_active` | TIMESTAMP | Обновляется через `UpdateLastActive` |
| `is_deleted` | BOOLEAN | Soft-delete |
| `deleted_at` | TIMESTAMP | — |

> **Про `username`:** намеренно не уникален — поиск пользователя происходит
> по `user_public_key` (QR-код). `@username` не является уникальным хендлом.

### `user_settings`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `user_id` | UUID FK → `users.id` | — |
| `theme` | VARCHAR | `light` / `dark` / `system` |
| `language` | VARCHAR | — |
| `is_translator_active` | BOOLEAN | — |
| `animations_enabled` | BOOLEAN | — |
| `account_auto_delete_after_days` | INTEGER | NULL = отключено |
| `profile_visible_to` | VARCHAR | `everybody` / `contacts` / `nobody` |
| `last_active_visible_to` | VARCHAR | `everybody` / `contacts` / `nobody` |
| `chat_background_media_id` | UUID | — |
| `top_bar_color` | VARCHAR | HEX-цвет |
| `ringtone_media_id` | UUID | — |
| `ringtone_vibration_strength` | INTEGER | — |
| `notification_sound` | UUID | — |
| `notification_vibration_strength` | INTEGER | — |
| `updated_at` | TIMESTAMP | Обновляется при каждом `UpdateUserSettings` |

---

## gRPC API

### `CreateUser(CreateUserRequest) → UserProfileResponse`
Вызывается оркестратором сразу после успешного `auth.Register`.

**Вход:** `username` (тело), `x-user-id` (gRPC metadata)

**Логика:**

1. Создание `users` с `id = x-user-id`
2. Заполнение `username`
3. Создание `user_settings` с дефолтными значениями

---

### `GetUser(GetUserRequest) → UserProfileResponse`
**Вход:** `user_id`, `requester_user_id`

**Логика (apply_privacy):**
1. `requester = owner` → полный профиль
2. `profile_visible_to = nobody` → минимальный профиль (`id`, `username`, `is_deleted`)
3. `profile_visible_to = contacts` → вызов `contacts-service.IsContact(owner, requester)`.
   При недоступности contacts-service: **graceful fallback = False** → минимальный профиль
4. `last_active_visible_to = contacts` → аналогичная проверка
5. `last_active_visible_to = everybody` → включается в ответ
6. `last_active_visible_to = nobody` → не включается

> **Текущий статус:** contacts-service ещё не задеплоен. Все проверки
> `contacts` возвращают `False` через graceful fallback. Профили с
> `visible_to=contacts` временно выглядят как `nobody`.

---

### `GetUserByUserPublicKey(GetUserByUserPublicKeyRequest) → UserProfileResponse`
Поиск по `user_public_key` (base64). Та же privacy-логика, что в `GetUser`.
Используется при добавлении в контакты (клиент сканирует QR).

---

### `UpdateUser(UpdateUserRequest) → UserProfileResponse`
Patch-семантика: обновляются только переданные поля.

**Вход:** `user_id`, `bio?`, `username?`, `avatar_media_id?`, `profile_background_media_id?`

---

### `DeleteUser(DeleteUserRequest) → DeleteUserResponse`
Soft-delete: `is_deleted = true`, `deleted_at = now()`.

---

### `CheckAndProcessAutoDelete → CheckAndProcessAutoDeleteResponse`

**Логика:**
1. SELECT пользователей где `last_active < now() - account_auto_delete_after_days`
   AND `is_deleted = false` AND `account_auto_delete_after_days IS NOT NULL`
2. Для каждого вызывается `DeleteUser`

**Возврат:** `deleted_count`, `[]user_ids`

> ⚠️ В оркестраторе нет HTTP-роута или scheduler для вызова этого метода.
> Метод не будет срабатывать автоматически до добавления периодического вызова (cron/scheduler).

---

### `GetUserSettings(GetUserSettingsRequest) → UserSettingsResponse`
Полный объект `user_settings`. Только для владельца.

---

### `UpdateUserSettings(UpdateUserSettingsRequest) → UserSettingsResponse`
Patch-семантика через `google.protobuf.FieldMask`.

> ⚠️ Нельзя использовать семантику "числовое поле обновляется если `!= 0`" —
> это исключает возможность явно выставить нулевое значение (например,
> `ringtone_vibration_strength = 0`). Все поля, включая числовые, должны
> обновляться только если они присутствуют в `FieldMask`, либо использовать
> обёрточные типы (`google.protobuf.Int32Value`).

---

### Internal-методы

#### `GetUsersBatch → GetUsersBatchResponse`
Массовое получение кратких профилей **без проверки приватности**.
**Кто вызывает:** contacts-service, groups-service, notifications-service.
**Возврат:** `[]{ id, username, avatar_media_id, is_deleted }`

> ⚠️ Метод не требует аутентификации вызывающего сервиса. До введения
> межсервисной аутентификации (mTLS или service token) порт 50052 должен
> быть закрыт на сетевом уровне.

#### `UserExists → UserExistsResponse`
**Кто вызывает:** contacts-service перед `AddContact`.
**Возврат:** `exists: bool`, `is_deleted: bool`

#### `UpdateLastActive → UpdateLastActiveResponse`
Debounce: обновляет `last_active` не чаще раза в 60 сек через
Redis-ключ `last_active_debounce:<user_id>`.

**Кто вызывает:** Orchestrator middleware после каждого запроса.

> ⚠️ Middleware в оркестраторе **не реализован**. Метод готов, но не вызывается.
> Нужно добавить:
> ```python
> @app.middleware("http")
> async def update_last_active_middleware(request: Request, call_next):
>     response = await call_next(request)
>     session = getattr(request.state, "session", None)
>     if session:
>         asyncio.create_task(user_gateway.update_last_active(session.user_id))
>     return response
> ```

#### `GetPrivacySettings → GetPrivacySettingsResponse`
Только настройки приватности без полного профиля.
**Кто вызывает:** contacts-service.
**Возврат:** `profile_visible_to`, `last_active_visible_to`

---

## Зависимости

**Текущая (contacts-service не задеплоен):**

user-service

├── PostgreSQL (users, user_settings)

├── Redis (last_active debounce)

└── contacts-service (IsContact) ← graceful fallback при недоступности


**Плановая (после деплоя contacts-service):**

orchestrator → user-service.GetUser ← без contacts-проверки

orchestrator → contacts-service.IsContact ← оркестратор сам фильтрует

user-service **перестаёт вызывать** contacts-service, циклическая зависимость устраняется.
