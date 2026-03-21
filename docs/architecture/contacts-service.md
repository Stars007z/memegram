# contacts-service — Architecture

## Стек
| Компонент | Технология |
|-----------|-----------|
| Runtime | Python 3.12 |
| Transport | gRPC (grpcio 1.78) + gRPC Reflection |
| БД | PostgreSQL 15 (asyncpg + SQLAlchemy 2.0 async) |
| Миграции | Alembic |
| Порт | `50053` |

> Redis не нужен на старте — все операции простые EXISTS/SELECT.

---

## База данных

### `contacts`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `user_id` | UUID NOT NULL | Тот, кто добавил |
| `contact_user_id` | UUID NOT NULL | Кого добавили |
| `created_at` | TIMESTAMP | — |
| `is_favorite` | BOOLEAN DEFAULT false | Избранный |

**Индексы:** UNIQUE(`user_id`, `contact_user_id`), INDEX(`user_id`)

### `blocked_users`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `user_id` | UUID NOT NULL | Тот, кто заблокировал |
| `blocked_user_id` | UUID NOT NULL | Кого заблокировали |
| `created_at` | TIMESTAMP | — |

**Индексы:** UNIQUE(`user_id`, `blocked_user_id`), INDEX(`user_id`)

---

## Зависимости

contacts-service

├── PostgreSQL (contacts, blocked_users)

└── user-service (UserExists, GetUsersBatch, GetPrivacySettings)


---

## gRPC API

### Контакты

#### `AddContact(AddContactRequest) → AddContactResponse`

**Вход:** `user_id`, `user_public_key` (публичный ключ того, кого добавляем)

**Логика:**
1. `user-service.GetUserByUserPublicKey(user_public_key)` → получить `contact_user_id`
2. Проверка `user_id ≠ contact_user_id` → `INVALID_ARGUMENT`
3. `user-service.UserExists(contact_user_id)` → если `!exists` или `is_deleted`: `NOT_FOUND`
4. `IsBlocked(user_id, contact_user_id)` — если мы сами заблокировали этого пользователя: `NOT_FOUND`
5. `IsBlocked(contact_user_id, user_id)` — если нас заблокировали: `NOT_FOUND`
   (в обоих случаях не раскрываем факт блокировки)
6. Проверка уникальности `(user_id, contact_user_id)` → если есть: `ALREADY_EXISTS`
7. INSERT в `contacts`
8. `user-service.GetUsersBatch([contact_user_id])` — обогащение кратким профилем

**Возврат:** `ContactEntry { contact_user_id, is_favorite, created_at, profile: UserBriefProfile }`

---

#### `RemoveContact(RemoveContactRequest) → RemoveContactResponse`

**Вход:** `user_id`, `contact_user_id`

**Логика:**
1. SELECT WHERE `(user_id, contact_user_id)` → если нет: `NOT_FOUND`
2. DELETE

**Возврат:** `success: bool`

---

#### `GetContacts(GetContactsRequest) → GetContactsResponse`

**Вход:** `user_id`, `limit: int`, `offset: int`

**Логика:**
1. SELECT FROM `contacts` WHERE `user_id = ?`
   ORDER BY `is_favorite DESC, created_at ASC`
   LIMIT/OFFSET
2. COUNT(*) для `total_count`
3. `user-service.GetUsersBatch(contact_user_ids)` → краткие профили
4. JOIN записей с профилями

**Возврат:** `[]ContactEntry`, `total_count: int`

---

#### `UpdateContact(UpdateContactRequest) → UpdateContactResponse`

**Вход:** `user_id`, `contact_user_id`, `is_favorite?: bool`

**Логика:**
1. SELECT WHERE `(user_id, contact_user_id)` → если нет: `NOT_FOUND`
2. UPDATE переданных полей (patch-семантика)

**Возврат:** обновлённый `ContactEntry`

---

### Блокировки

#### `BlockUser(BlockUserRequest) → BlockUserResponse`

**Вход:** `user_id`, `blocked_user_id`

**Логика (в одной транзакции):**
1. Проверка `user_id ≠ blocked_user_id` → `INVALID_ARGUMENT`
2. SELECT EXISTS `(user_id, blocked_user_id)` → если уже есть: `ALREADY_EXISTS` (idempotent)
3. INSERT в `blocked_users`
4. DELETE FROM `contacts` WHERE `user_id = user_id AND contact_user_id = blocked_user_id`
5. DELETE FROM `contacts` WHERE `user_id = blocked_user_id AND contact_user_id = user_id`
   (взаимный контакт)

**Возврат:** `success: bool`, `created_at: int64` (Unix timestamp)

---

#### `UnblockUser(UnblockUserRequest) → UnblockUserResponse`

**Вход:** `user_id`, `blocked_user_id`

**Логика:**
1. SELECT WHERE `(user_id, blocked_user_id)` → если нет: `NOT_FOUND`
2. DELETE

**Возврат:** `success: bool`

---

#### `GetBlockedUsers(GetBlockedUsersRequest) → GetBlockedUsersResponse`

**Вход:** `user_id`, `limit: int`, `offset: int`

**Логика:**
1. SELECT FROM `blocked_users` WHERE `user_id = ?` с пагинацией
2. COUNT(*) для `total_count`
3. `user-service.GetUsersBatch(blocked_user_ids)` → краткие профили

**Возврат:** `[]BlockedEntry { blocked_user_id, blocked_at, profile: UserBriefProfile }`,
`total_count: int`

---

### Internal-методы

#### `IsContact(IsContactRequest) → IsContactResponse`

```sql
SELECT EXISTS(
  SELECT 1 FROM contacts
  WHERE user_id = ? AND contact_user_id = ?
)
```

**Вход:** `user_id`, `contact_user_id`
**Возврат:** `is_contact: bool`

#### `IsBlocked(IsBlockedRequest) → IsBlockedResponse`

```sql
SELECT EXISTS(
  SELECT 1 FROM blocked_users
  WHERE user_id = ? AND blocked_user_id = ?
)
```

**Вход:** `user_id`, `blocked_user_id`
**Возврат:** `is_blocked: bool`
