# auth-service — Architecture

## Стек
| Компонент | Технология |
|-----------|-----------|
| Runtime | Python 3.12 |
| Transport | gRPC (grpcio 1.78) + gRPC Reflection |
| БД | PostgreSQL 15 (asyncpg + SQLAlchemy 2.0 async) |
| Кеш / challenge store | Redis 7 |
| Аутентификация | JWT (HS256, PyJWT) + Ed25519 (cryptography) |
| Миграции | Alembic |
| Порт | `50051` |

---

## База данных

### `devices`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | Внутренний ID устройства (возвращается клиенту как `device_id`) |
| `user_id` | UUID | Владелец (FK на user-service, без constraint) |
| `client_device_id` | VARCHAR(255) UNIQUE | Внешний идентификатор, предоставленный клиентом при регистрации |
| `device_name` | VARCHAR(255) | Человекочитаемое название |
| `device_type` | VARCHAR(50) | `primary` / `secondary` |
| `is_active` | BOOLEAN | Активно ли устройство |
| `identity_key_pub` | BYTEA NOT NULL | Ed25519 публичный ключ (верификация подписи) |
| `init_key_pub` | BYTEA NOT NULL | Публичный ключ инициализации (MLS/key exchange) |
| `credential_data` | BYTEA NOT NULL | Сертификаты/подписи устройства |
| `last_seen` | TIMESTAMP | Время последнего успешного логина |
| `revoked_at` | TIMESTAMP | Время отзыва |
| `revoked_by_device_id` | UUID | Кто отозвал |

> **Важно:** Клиент при логине передаёт `device_id` = значение `devices.id` (UUID),
> который вернул сервер при регистрации. Метод `get_device_by_id()` делает lookup по
> первичному ключу `id`, а не по полю `client_device_id`.

> **Про `device_type`:** тип `admin` исключён из схемы — нет определённого flow
> его присвоения. Если понадобится расширенный доступ, нужно отдельно
> описать механизм повышения прав.

### `sessions`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `device_id` | UUID FK → `devices.id` CASCADE | — |
| `access_token` | VARCHAR(512) UNIQUE | JWT access token |
| `refresh_token` | VARCHAR(512) UNIQUE | JWT refresh token |
| `expires_at` | TIMESTAMP | Срок действия access token |
| `refresh_expires_at` | TIMESTAMP | Срок действия refresh token |
| `last_used` | TIMESTAMP | Время последнего использования (logout, validate) |
| `is_revoked` | BOOLEAN | Отозвана ли сессия |

### `invites`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `code` | VARCHAR(64) UNIQUE | Инвайт-код |
| `is_used` | BOOLEAN | Использован ли |
| `used_by_user_id` | UUID | Кем использован |
| `expires_at` | TIMESTAMP | Срок действия |

### `device_registration`
Временная таблица для flow добавления нового устройства.
Статусы: `pending` → `awaiting_confirmation` → `confirmed` / `rejected`.

---

## gRPC API

### `Register(RegisterRequest) → AuthResponse`
**Вход:** `username`, `invite_code`, `device_id`, `device_name`,
`identity_key_pub`, `init_key_pub`, `credential_data`

**Логика:**
1. Валидация инвайта (существование, не использован, не просрочен)
2. Генерация `user_id` (UUID) и `device_uuid` (UUID)
3. Создание записи в `devices` (device_type = "primary")
4. Генерация JWT access + refresh токенов
5. Создание сессии
6. Пометка инвайта как использованного

**Возврат:** `user_id`, `device_id` (= `devices.id`), `is_primary`,
`access_token`, `refresh_token`, `expires_at`


---

### `LoginInit(LoginInitRequest) → LoginInitResponse`
**Вход:** `device_id` (UUID, выданный при регистрации)

**Логика:**
1. Lookup устройства по `devices.id`
2. Проверка `is_active = true`
3. Генерация challenge (32 случайных байта)
4. Сохранение в Redis: `auth:challenge:<device_id>` TTL=300s
5. Возврат challenge в base64

**Возврат:** `challenge` (base64), `expires_at`, `device_id`

---

### `LoginComplete(LoginCompleteRequest) → AuthResponse`
**Вход:** `device_id`, `challenge` (base64), `signature` (Ed25519, bytes), `device_name?`

**Логика:**
1. Декодирование challenge из base64 → raw bytes
2. Получение challenge из Redis, сверка
3. Lookup устройства, проверка `is_active`
4. Верификация подписи Ed25519: клиент подписывает **raw bytes** challenge (до base64-кодирования)
5. Удаление challenge из Redis (одноразовое)
6. Обновление `devices.last_seen` (и `device_name` если передан)
7. Генерация токенов, создание сессии

**Возврат:** `user_id`, `device_id`, `is_primary`, `access_token`, `refresh_token`, `expires_at`

---

### `RefreshToken(RefreshTokenRequest) → AuthResponse`
**Вход:** `refresh_token`

**Логика:**
1. Поиск сессии по `refresh_token`
2. Проверка `is_revoked = false` и `refresh_expires_at > now()`
3. Отзыв старой сессии (`is_revoked = true`)
4. Генерация новых access + refresh токенов
5. Создание новой сессии

**Возврат:** `user_id`, `device_id`, `is_primary`, `access_token`, `refresh_token`, `expires_at`

---

### `Logout(LogoutRequest) → LogoutResponse`
**Вход:** `access_token`

**Логика:**
1. Поиск сессии по `access_token`
2. Проверка соответствия `device_id` из JWT и из БД
3. `session.is_revoked = true`, обновление `sessions.last_used`
4. Инвалидация Redis-кеша `session:valid:<access_token>`

---

### `ValidateToken(ValidateTokenRequest) → ValidateTokenResponse`
Используется оркестратором для проверки каждого запроса.

**Логика:**
1. Проверка Redis-кеша `session:valid:<access_token>`
2. Декодирование JWT
3. Проверка сессии в БД (не отозвана ли)
4. Получение `device_type` из `devices`
5. Кеширование результата в Redis до истечения TTL токена

**Возврат:** `valid`, `user_id`, `device_id`, `device_type`, `expires_at`

---

### `CreateInvite(CreateInviteRequest) → CreateInviteResponse`
**Вход:** `expires_in_days` (1–365), `created_by_device_id?`

**Возврат:** `code`, `created_at`, `expires_at`, `is_used`, `message`

---

### `HealthCheck(HealthCheckRequest) → HealthCheckResponse`
Проверяет PostgreSQL (`SELECT 1`) и Redis (ping).

**Возврат:** `status` (`ok`/`degraded`), `db_status`, `redis_status`, `version`

---

## Зависимости

auth-service

├── PostgreSQL (devices, sessions, invites, device_registration)

└── Redis (challenge store, session validation cache)
