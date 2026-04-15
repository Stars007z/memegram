# messaging-service — Architecture

## Стек
| Компонент | Технология |
|-----------|-----------|
| Runtime | Python 3.12 |
| Transport | gRPC (grpcio 1.78) + gRPC Reflection |
| БД | PostgreSQL 15 (asyncpg + SQLAlchemy 2.0 async) |
| Кеш / real-time | Redis 7 |
| Миграции | Alembic |
| Порт | `50054` |

---

## Архитектурное решение: монолит vs split

Messaging-service — **единый сервис** (не дробим дальше). Медиа выделены в отдельный `media-service` (порт `50055`), который отвечает только за взаимодействие с S3. Это оправдано тем, что нагрузка на медиа (большие бинарные объёмы, presigned URL) принципиально отличается от нагрузки на сообщения (много мелких записей).

---

## Real-time: WebSocket vs отдельный Gateway

**Рекомендация для MVP:** gRPC Server-Streaming внутри messaging-service.

| Критерий | WebSocket Gateway (отдельный) | gRPC Server-Streaming (в сервисе) |
|----------|-------------------------------|-----------------------------------|
| Сложность на старте | Высокая (ещё один сервис, роутинг) | Низкая (всё в одном месте) |
| Масштабирование | Лучше (gateway stateless) | Хуже (стримы держат соединения) |
| Совместимость с клиентом | Нужен gRPC-Web или HTTP upgrade | Нативно для gRPC-клиентов |
| Путь миграции | — | Легко вынести gateway позже |

**Решение для MVP:** `SubscribeToConversation` — Server-Streaming метод. Redis Pub/Sub используется для фан-аута между инстансами сервиса. При росте нагрузки стриминг выносится в отдельный gateway без изменения логики хранения.

---

## База данных (PostgreSQL)

### `conversations`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `type` | VARCHAR(20) | `direct` / `group` |
| `name` | VARCHAR(255) NULLABLE | Только для group |
| `avatar_media_id` | UUID NULLABLE | ID ресурса в `item-storage-service` (`group_avatar`). Только для group |
| `created_by_user_id` | UUID | — |
| `last_message_id` | UUID NULLABLE | FK на `messages.id` (без constraint — circular) |
| `last_activity_at` | TIMESTAMP | Для сортировки списка чатов |
| `created_at` | TIMESTAMP | — |

**Индексы:** INDEX(`last_activity_at` DESC)

> Для direct-чатов уникальность пары участников обеспечивается на уровне логики создания + UNIQUE INDEX по отсортированной паре `(user_id_a, user_id_b)` через partial index.

---

### `conversation_members`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `conversation_id` | UUID FK → `conversations.id` CASCADE | — |
| `user_id` | UUID | — |
| `role` | VARCHAR(20) | `owner` / `admin` / `member` |
| `joined_at` | TIMESTAMP | — |
| `left_at` | TIMESTAMP NULLABLE | NULL = активный участник |
| `last_read_message_id` | UUID NULLABLE | Для счётчика непрочитанных |

**Индексы:** UNIQUE(`conversation_id`, `user_id`), INDEX(`user_id`)

---

### `messages`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `conversation_id` | UUID FK → `conversations.id` | — |
| `sender_user_id` | UUID | — |
| `sender_device_id` | UUID | Устройство-отправитель |
| `type` | VARCHAR(20) | `text` / `image` / `video` / `audio` / `file` / `system` |
| `mls_ciphertext` | BYTEA NOT NULL | Зашифрованный MLS MLSCiphertext (сервер не видит plaintext) |
| `mls_epoch` | BIGINT | Эпоха MLS-группы на момент отправки |
| `media_id` | UUID NULLABLE | FK → `media_attachments.id` |
| `reply_to_message_id` | UUID NULLABLE | Цитируемое сообщение |
| `client_message_id` | UUID UNIQUE | Idempotency key от клиента |
| `created_at` | TIMESTAMP | — |
| `edited_at` | TIMESTAMP NULLABLE | — |
| `deleted_at` | TIMESTAMP NULLABLE | Soft-delete |

**Индексы:** INDEX(`conversation_id`, `created_at` DESC), INDEX(`sender_user_id`)

> `type` нужен только для UI-плейсхолдера ("фото", "видео") до расшифровки на клиенте. Само содержимое хранится исключительно в `mls_ciphertext`.

> **Партиционирование:** `PARTITION BY RANGE (created_at)` — по месяцам. Подключается по мере роста объёма.

---

### `media_attachments`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `uploader_user_id` | UUID | — |
| `conversation_id` | UUID | — |
| `s3_key` | TEXT NULLABLE | NULL до подтверждения загрузки |
| `mime_type` | VARCHAR(100) | `image/jpeg`, `video/mp4`, … |
| `encrypted_size` | BIGINT | Размер зашифрованного файла в байтах |
| `encryption_metadata` | BYTEA | Зашифрованный симметричный ключ + IV в MLS-обёртке |
| `created_at` | TIMESTAMP | — |
| `confirmed_at` | TIMESTAMP NULLABLE | NULL = загрузка не подтверждена |
| `expires_at` | TIMESTAMP NULLABLE | TTL для временных медиа (напр. голосовые) |

---

### `mls_groups`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | Совпадает с `conversations.id` |
| `mls_group_id` | BYTEA UNIQUE | Бинарный GroupID из MLS (RFC 9420) |
| `current_epoch` | BIGINT | Текущая эпоха группы |
| `cipher_suite` | INTEGER | MLS CipherSuite (напр. 1 = MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519) |
| `ratchet_tree` | BYTEA NULLABLE | Последний публичный ratchet tree для Welcome новым участникам |
| `updated_at` | TIMESTAMP | — |

---

### `mls_key_packages`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `user_id` | UUID | — |
| `device_id` | UUID | — |
| `key_package_data` | BYTEA NOT NULL | Сериализованный KeyPackage (TLS encoding) |
| `key_package_ref` | BYTEA UNIQUE NOT NULL | KeyPackageRef (хеш пакета, используется в Welcome/Add) |
| `cipher_suite` | INTEGER | — |
| `created_at` | TIMESTAMP | — |
| `consumed_at` | TIMESTAMP NULLABLE | NULL = доступен; NOT NULL = уже использован в Add |

**Индексы:** INDEX(`user_id`, `device_id`) WHERE `consumed_at IS NULL`

> KeyPackage — одноразовый. После использования помечается `consumed_at`. Клиент загружает пакеты заблаговременно (рекомендуемый батч — 50 штук). Если доступных пакетов мало — сервер уведомляет клиента.

---

### `mls_welcome_messages`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `recipient_device_id` | UUID | Для какого устройства Welcome |
| `conversation_id` | UUID | — |
| `welcome_data` | BYTEA NOT NULL | Сериализованный MLS Welcome (непрозрачный blob) |
| `created_at` | TIMESTAMP | — |
| `delivered_at` | TIMESTAMP NULLABLE | NULL = ещё не получен клиентом |

> Welcome хранится на сервере до момента получения клиентом. Содержит зашифрованное состояние группы, необходимое новому участнику для инициализации.

---

### `mls_commit_messages`
| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | UUID PK | — |
| `conversation_id` | UUID | — |
| `sender_device_id` | UUID | — |
| `epoch` | BIGINT | Эпоха, к которой применяется Commit |
| `commit_data` | BYTEA NOT NULL | Сериализованный MLS PublicMessage (Commit) |
| `created_at` | TIMESTAMP | — |

**Constraints:** UNIQUE(`conversation_id`, `epoch`)

> Хранятся для синхронизации устройств, которые были оффлайн во время Commit. Устройство запрашивает все Commit'ы с момента своей последней известной эпохи.
>
> ⚠️ UniqueConstraint гарантирует, что для каждой эпохи в группе может существовать ровно один Commit. Это предотвращает race condition, когда несколько участников одновременно пытаются применить Commit к одной эпохе (например, два участника одновременно создают Remove Commit при `member_left`). Проигравший получает `ABORTED` и должен синхронизироваться.

---

## Зависимости

```
messaging-service
├── PostgreSQL  (conversations, messages, mls_*, media_attachments)
├── Redis       (typing indicators, online presence, unread cache, pub/sub фан-аут)
├── media-service         (presigned URLs для S3)
├── contacts-service      (IsBlocked при создании direct-чата)
├── auth-service          (входящий вызов NotifyDeviceRevoked)
└── item-storage-service  (хранение аватаров групп — только ссылка avatar_media_id)
```

---

## gRPC API

### MLS Key Material

#### `UploadKeyPackages(UploadKeyPackagesRequest) → UploadKeyPackagesResponse`
Клиент загружает батч KeyPackage на сервер заблаговременно.

**Вход:**
- `user_id` UUID
- `device_id` UUID
- `key_packages` repeated bytes — TLS-сериализованные KeyPackage

**Логика:**
1. Для каждого пакета — парсинг `key_package_ref` (хеш)
2. Проверка cipher_suite на поддерживаемость
3. Batch INSERT в `mls_key_packages`

**Возврат:** `uploaded_count: int`

---

#### `GetKeyPackage(GetKeyPackageRequest) → GetKeyPackageResponse`
Выдаёт одну незанятую KeyPackage указанного устройства. Вызывается инициатором при создании группы или Add участника.

**Вход:**
- `target_user_id` UUID
- `target_device_id` UUID

**Логика:**
1. SELECT незанятый пакет (`consumed_at IS NULL`), FOR UPDATE SKIP LOCKED
2. UPDATE `consumed_at = now()`

**Возврат:** `key_package_data: bytes`, `key_package_ref: bytes`

**Ошибки:** `NOT_FOUND` — нет доступных пакетов

---

#### `GetKeyPackagesCount(GetKeyPackagesCountRequest) → GetKeyPackagesCountResponse`
Проверка количества незанятых пакетов. Оркестратор периодически вызывает и пушит уведомление, если запас иссяк.

**Вход:** `user_id`, `device_id`

**Возврат:** `available_count: int`

---

### Чаты (Conversations)

#### `CreateDirectConversation(CreateDirectConversationRequest) → ConversationResponse`
Создание личного чата 1-1. Инициатор предварительно запрашивает KeyPackage получателя и формирует MLS Welcome на клиенте.

**Вход:**
- `initiator_user_id` UUID
- `initiator_device_id` UUID
- `recipient_user_id` UUID
- `welcome_messages` repeated `{ device_id: UUID, welcome_data: bytes }` — Welcome для каждого устройства получателя

**Логика:**
1. `contacts-service.IsBlocked` в обе стороны → `NOT_FOUND` при блокировке
2. Проверка что direct-чат между этой парой не существует → `ALREADY_EXISTS`
3. INSERT `conversations` (type=`direct`)
4. INSERT двух `conversation_members`
5. INSERT `mls_groups`
6. INSERT `mls_welcome_messages` для каждого устройства из `welcome_messages`

**Возврат:** `ConversationResponse { id, type, members[], created_at }`

---

#### `CreateGroupConversation(CreateGroupConversationRequest) → ConversationResponse`
Создание группового чата.

**Вход:**
- `creator_user_id` UUID
- `creator_device_id` UUID
- `name` string
- `members` repeated `{ user_id: UUID, welcomes: [{ device_id, welcome_data }] }`

**Логика:**
1. Для каждого участника — `contacts-service.IsBlocked`
2. INSERT `conversations` (type=`group`), `conversation_members` (creator = `owner`, остальные = `member`)
3. INSERT `mls_groups`
4. INSERT `mls_welcome_messages` для каждого устройства каждого участника

**Возврат:** `ConversationResponse`

---

#### `GetConversations(GetConversationsRequest) → GetConversationsResponse`
Список чатов пользователя с cursor-based пагинацией.

**Вход:** `user_id`, `limit: int`, `cursor?: string` (opaque, кодирует `last_activity_at + id`)

**Возврат:** `items[]` ConversationSummary:
- `id`, `type`, `name?`
- `avatar_media_id?` — ID аватарки группы из `item-storage-service` (только для `group`)
- `last_message_type` — тип последнего сообщения (`text`/`image`/…), **не содержимое**
- `unread_count: int` — из Redis-кеша или COUNT(*) по `messages`
- `last_activity_at`

---

#### `GetConversation(GetConversationRequest) → ConversationResponse`
Полные данные одного чата включая список участников.

**Вход:** `user_id`, `conversation_id`

**Возврат:** `ConversationResponse { id, type, name, avatar_media_id?, members[], mls_group: { current_epoch, cipher_suite } }`

---

#### `LeaveConversation(LeaveConversationRequest) → LeaveConversationResponse`
Участник покидает группу.

**Вход:** `user_id`, `device_id`, `conversation_id`, `commit_data?: bytes` (optional, не используется)

**Логика:**
1. Проверка членства
2. UPDATE `conversation_members.left_at = now()`
3. Redis PUBLISH событие `member_left: { user_id, conversation_id }` в канал чата

**Возврат:** `success: bool`

> ⚠️ **RFC 9420 §12.2:** Участник НЕ может закоммитить своё собственное удаление из MLS-группы. `leave_group()` в OpenMLS возвращает Proposal, а не Commit. Поэтому сервер НЕ вызывает `CommitGroupChange` при выходе.
>
> **Протокол выхода:**
> 1. Уходящий клиент вызывает `leaveConversation()` → сервер помечает участника как ушедшего и публикует SSE `member_left`
> 2. Клиент локально удаляет MLS-группу из OpenMLS storage (`group.delete()`) и очищает маппинги
> 3. Оставшиеся участники, получив `member_left`, создают MLS Remove Commit через `remove_member_by_identity()` и отправляют его на сервер через `CommitGroupChange`
> 4. Благодаря UniqueConstraint на `(conversation_id, epoch)`, только один Remove Commit будет принят; остальные получат `ABORTED` и синхронизируются
>
> **Rejoin:** При повторном добавлении участника в группу (через Welcome), `CommitGroupChange` обрабатывает `added_user_ids` с трёхсторонней логикой: если участник имеет запись с `left_at != NULL` — UPDATE (обнуление `left_at`), если активный — skip, если нет записи — INSERT.

---

#### `KickMember(KickMemberRequest) → KickMemberResponse`
Администратор удаляет участника из группы (серверная часть — пометка + SSE-событие).

**Вход:**
- `user_id` UUID — вызывающий (admin/owner)
- `conversation_id` UUID
- `target_user_id` UUID — удаляемый участник

**Логика:**
1. Проверка, что вызывающий — active member с ролью `owner` или `admin`
2. Проверка, что `target_user_id != user_id` (для выхода — используй `LeaveConversation`)
3. Проверка, что target — active member
4. Нельзя кикнуть `owner`
5. Admin может кикнуть только `member`; `owner` может кикнуть и `admin`, и `member`
6. UPDATE `conversation_members.left_at = now()` для target
7. Redis PUBLISH событие `member_kicked: { user_id, kicked_by }`

**Возврат:** `success: bool`

> **MLS Remove Commit:** После успешного kick серверная часть только помечает участника как ушедшего. Админ-клиент, получив успешный ответ, создаёт MLS Remove Commit через `removeMemberByIdentity()` и отправляет через `CommitGroupChange`. Это аналогично тому, как другие участники создают Remove Commit при `member_left`.

---

#### `UpdateMemberRole(UpdateMemberRoleRequest) → UpdateMemberRoleResponse`
Изменение роли участника группы (promote/demote).

**Вход:**
- `user_id` UUID — вызывающий (admin/owner)
- `conversation_id` UUID
- `target_user_id` UUID
- `new_role` string — `"admin"` или `"member"`

**Логика:**
1. Валидация `new_role` ∈ {`admin`, `member`}
2. Проверка, что вызывающий — active member с ролью `owner` или `admin`
3. Нельзя менять собственную роль
4. Нельзя менять роль `owner`
5. Только `owner` может снять `admin` → `member` (демоушн)
6. UPDATE `conversation_members.role = new_role`
7. Redis PUBLISH событие `role_changed: { user_id, new_role }`

**Возврат:** `success: bool`

---

#### `UpdateGroupAvatar(UpdateGroupAvatarRequest) → UpdateGroupAvatarResponse`
Обновление аватарки группового чата. Изображение предварительно загружается в `item-storage-service` с `item_type = group_avatar`.

**Вход:**
- `user_id` UUID — вызывающий (owner/admin)
- `conversation_id` UUID
- `avatar_media_id` UUID NULLABLE — `item_id` из `item-storage-service`; пустая строка для удаления аватарки

**Логика:**
1. Проверка что `conversation.type = group` → `FAILED_PRECONDITION`
2. Проверка, что вызывающий — active member с ролью `owner` или `admin` → `PERMISSION_DENIED`
3. UPDATE `conversations.avatar_media_id`
4. Redis PUBLISH событие `group_avatar_changed: { avatar_media_id, changed_by }`

**Возврат:** `success: bool`

> **Загрузка аватарки (поток):**
> 1. Клиент вызывает `item-storage-service.InitiateUpload(item_type="group_avatar", ...)` через оркестратор → получает `item_id` + presigned PUT URL
> 2. Клиент загружает изображение в S3 по presigned URL
> 3. Клиент вызывает `item-storage-service.ConfirmUpload(item_id)`
> 4. Клиент вызывает `messaging-service.UpdateGroupAvatar(conversation_id, avatar_media_id=item_id)` через оркестратор
>
> Для отображения: клиент получает `avatar_media_id` из `GetConversations` / `GetConversation` и запрашивает presigned download URL через `item-storage-service.GetDownloadUrl`.

---

#### `UpdateGroupName(UpdateGroupNameRequest) → UpdateGroupNameResponse`
Изменение названия группового чата.

**Вход:**
- `user_id` UUID — вызывающий (owner/admin)
- `conversation_id` UUID
- `name` string — новое название (1–255 символов)

**Логика:**
1. Валидация `name` — не пустая строка → `INVALID_ARGUMENT`
2. Проверка что `conversation.type = group` → `FAILED_PRECONDITION`
3. Проверка, что вызывающий — active member с ролью `owner` или `admin` → `PERMISSION_DENIED`
4. UPDATE `conversations.name`
5. Redis PUBLISH событие `group_name_changed: { name, changed_by }`

**Возврат:** `success: bool`

---

### Сообщения

#### `SendMessage(SendMessageRequest) → SendMessageResponse`
Отправка зашифрованного сообщения.

**Вход:**
- `sender_user_id` UUID
- `sender_device_id` UUID
- `conversation_id` UUID
- `mls_ciphertext: bytes` — MLS MLSCiphertext (RFC 9420)
- `type` — `text` / `image` / `video` / `audio` / `file`
- `media_id?: UUID`
- `reply_to_message_id?: UUID`
- `client_message_id: UUID` — idempotency key

**Логика:**
1. Проверка активного членства (`left_at IS NULL`)
2. Idempotency check по `client_message_id`
3. INSERT `messages`
4. UPDATE `conversations.last_message_id`, `last_activity_at`
5. Redis PUBLISH в канал `conv:{conversation_id}` → Server-Streaming подписчики получают событие

**Возврат:** `message_id: UUID`, `created_at: TIMESTAMP`, `server_sequence: int64`

---

#### `GetMessages(GetMessagesRequest) → GetMessagesResponse`
История сообщений с пагинацией (загрузка вверх).

**Вход:** `user_id`, `conversation_id`, `before_message_id?: UUID`, `limit: int` (max 100)

**Логика:**
1. Проверка членства
2. SELECT WHERE `conversation_id = ?` AND `created_at < (SELECT created_at FROM messages WHERE id = before_message_id)` ORDER BY `created_at` DESC LIMIT `limit`

**Возврат:** `messages[] MessageEntry`, `has_more: bool`

`MessageEntry`:
- `id`, `sender_user_id`, `sender_device_id`, `type`
- `mls_ciphertext: bytes`
- `media_id?`, `reply_to_message_id?`
- `mls_epoch: int64`
- `created_at`, `edited_at?`, `deleted_at?`

---

#### `EditMessage(EditMessageRequest) → EditMessageResponse`
Редактирование сообщения отправителем.

**Вход:** `user_id`, `device_id`, `message_id`, `new_mls_ciphertext: bytes`

**Логика:**
1. Проверка `sender_user_id = user_id` → `PERMISSION_DENIED`
2. Проверка `deleted_at IS NULL` → `NOT_FOUND`
3. UPDATE `mls_ciphertext`, `edited_at = now()`
4. Redis PUBLISH событие `message_edited`

**Возврат:** обновлённый `MessageEntry`

---

#### `DeleteMessage(DeleteMessageRequest) → DeleteMessageResponse`
Удаление сообщения.

**Вход:** `user_id`, `message_id`, `delete_for_everyone: bool`

**Логика:**
1. `delete_for_everyone = true` → только отправитель или admin/owner группы
2. Soft-delete: UPDATE `deleted_at = now()`, `mls_ciphertext = b''`
3. Redis PUBLISH событие `message_deleted`

**Возврат:** `success: bool`

---

#### `MarkAsRead(MarkAsReadRequest) → MarkAsReadResponse`
Обновление позиции прочтения и сброс счётчика непрочитанных.

**Вход:** `user_id`, `device_id`, `conversation_id`, `last_read_message_id: UUID`

**Логика:**
1. UPDATE `conversation_members.last_read_message_id`
2. DEL Redis-ключ `unread:{user_id}:{conv_id}`

**Возврат:** `unread_count: int`

---

#### `SubscribeToConversation(SubscribeRequest) → stream ConversationEvent`
Server-Streaming. Клиент держит стрим открытым для получения real-time событий.

**Вход:** `user_id`, `device_id`, `conversation_ids[] UUID`

**События (ConversationEvent):**
- `new_message: MessageEntry`
- `message_edited: { message_id, new_mls_ciphertext, edited_at }`
- `message_deleted: { message_id }`
- `typing: { user_id, conversation_id, is_typing }`
- `member_joined: { user_id, conversation_id }`
- `member_left: { user_id, conversation_id }` — триггер для оставшихся участников: получив это событие, один из них создаёт MLS Remove Commit и отправляет через `CommitGroupChange`
- `epoch_changed: { conversation_id, new_epoch }` — триггер для синхронизации MLS

**Логика:** Redis SUB на каналы `conv:{id}` для каждого из `conversation_ids`. При получении события — форвард в стрим.

---

### MLS Group Management

#### `CommitGroupChange(CommitGroupChangeRequest) → CommitGroupChangeResponse`
Применение MLS Commit (Add / Remove / Update). Единственный метод изменения состояния группы на сервере.

**Вход:**
- `user_id` UUID
- `device_id` UUID
- `conversation_id` UUID
- `commit_data: bytes` — сериализованный MLS PublicMessage (Commit)
- `new_epoch: int64` — ожидаемая новая эпоха
- `welcome_messages?: repeated { device_id, welcome_data: bytes }` — для новых участников (Add)
- `ratchet_tree?: bytes` — обновлённый публичный ratchet tree
- `removed_device_ids?: repeated UUID` — для Remove операций

**Логика:**
1. Проверка прав: Add/Remove — только `owner`/`admin`; Update — любой `member`
2. Оптимистичная проверка `current_epoch + 1 = new_epoch` → `ABORTED` при конфликте
3. INSERT `mls_commit_messages` внутри SAVEPOINT (`session.begin_nested()`):
   - При `IntegrityError` (UniqueConstraint на `conversation_id` + `epoch`) → `ABORTED` с сообщением об epoch conflict
   - Клиент при получении `ABORTED` должен выполнить `clearPendingCommit()` + `syncGroupCommits()` для синхронизации
4. INSERT `mls_welcome_messages` для каждого нового устройства (если Add)
5. UPDATE `mls_groups.current_epoch = new_epoch`, `ratchet_tree`
6. Обработка `added_user_ids` (трёхсторонняя логика для поддержки rejoin):
   - Если участник имеет запись с `left_at != NULL` → UPDATE: обнуление `left_at`, обновление `joined_at` и `role`
   - Если участник активен (`left_at IS NULL`) → skip
   - Если записи нет → INSERT новый `conversation_member`
7. Если есть `removed_device_ids` — UPDATE `conversation_members.left_at` для соответствующих user_id (если у пользователя не осталось активных устройств в группе)
8. Redis PUBLISH событие `epoch_changed` в канал чата

**Возврат:** `new_epoch: int64`, `committed_at: TIMESTAMP`

---

#### `GetPendingWelcomes(GetPendingWelcomesRequest) → GetPendingWelcomesResponse`
Получение Welcome-сообщений, ожидающих доставки устройству.

**Вход:** `device_id`

**Возврат:** `items[] { id, conversation_id, welcome_data: bytes, created_at }`

---

#### `AckWelcome(AckWelcomeRequest) → AckWelcomeResponse`
Подтверждение получения Welcome-сообщения.

**Вход:** `device_id`, `welcome_id: UUID`

**Логика:** UPDATE `delivered_at = now()`

**Возврат:** `success: bool`

---

#### `GetPendingCommits(GetPendingCommitsRequest) → GetPendingCommitsResponse`
Получение Commit'ов для синхронизации устройства после оффлайна.

**Вход:** `device_id`, `conversation_id`, `since_epoch: int64`

**Логика:** SELECT WHERE `conversation_id = ?` AND `epoch > since_epoch` ORDER BY `epoch` ASC

**Возврат:** `commits[] { epoch, commit_data: bytes, created_at }`

---

#### `NotifyDeviceRevoked(NotifyDeviceRevokedRequest) → NotifyDeviceRevokedResponse`
Internal. Вызывается auth-service при revoke устройства с основного устройства пользователя. Уведомляет все активные устройства пользователя через стрим чтобы они выполнили Remove Commit.

**Вход:** `user_id`, `revoked_device_id`

**Логика:**
1. Поиск всех активных `conversation_ids` где `user_id` является членом
2. Redis PUBLISH событие `device_revoked: { user_id, revoked_device_id, conversation_ids[] }` → клиент получает через `SubscribeToConversation`
3. Клиент (оставшееся устройство) формирует Remove Commit и вызывает `CommitGroupChange`

**Возврат:** `notified_conversations_count: int`

> ⚠️ До момента когда оставшееся устройство сделает CommitGroupChange, отозванное устройство технически ещё числится в группе по данным сервера. Это нормально для MLS: криптографически устройство уже не сможет расшифровать новые сообщения после Remove Commit.

---

### Медиа

#### `InitiateMediaUpload(InitiateMediaUploadRequest) → InitiateMediaUploadResponse`
Начало загрузки медиафайла. Возвращает presigned URL для прямой загрузки в S3.

**Вход:**
- `user_id` UUID
- `conversation_id` UUID
- `mime_type` string
- `encrypted_size: int64` — размер зашифрованного файла в байтах (ограничение: 100 МБ)
- `encryption_metadata: bytes` — зашифрованный ключ + IV в MLS-обёртке

**Логика:**
1. Проверка членства
2. Проверка `encrypted_size <= 104_857_600` (100 МБ)
3. INSERT `media_attachments` (без `s3_key`, `confirmed_at = NULL`)
4. Вызов `media-service.GetUploadPresignedUrl(media_id, mime_type)`

**Возврат:** `media_id: UUID`, `upload_url: string`, `expires_in: int` (секунды, обычно 3600)

---

#### `ConfirmMediaUpload(ConfirmMediaUploadRequest) → ConfirmMediaUploadResponse`
Подтверждение успешной загрузки в S3. Вызывается клиентом после PUT запроса по presigned URL.

**Вход:** `user_id`, `media_id`

**Логика:**
1. Проверка `uploader_user_id = user_id`
2. `media-service.VerifyObjectExists(media_id)` — проверка что файл действительно есть в S3
3. UPDATE `confirmed_at = now()`, `s3_key`

**Возврат:** `success: bool`

---

#### `GetMediaDownloadUrl(GetMediaDownloadUrlRequest) → GetMediaDownloadUrlResponse`
Получение временной ссылки для скачивания медиафайла.

**Вход:** `user_id`, `media_id`

**Логика:**
1. Проверка что `user_id` является членом чата медиафайла
2. Проверка `confirmed_at IS NOT NULL`
3. `media-service.GetDownloadPresignedUrl(s3_key)` → presigned GET URL (TTL 15 мин)

**Возврат:** `download_url: string`, `expires_in: int`, `encryption_metadata: bytes`

---

### Присутствие и набор текста

#### `SetTyping(SetTypingRequest) → SetTypingResponse`
Индикатор набора текста.

**Вход:** `user_id`, `device_id`, `conversation_id`, `is_typing: bool`

**Логика:**
- `is_typing = true` → SET Redis `typing:{conv_id}:{user_id}` TTL=5s + PUBLISH событие
- `is_typing = false` → DEL ключ + PUBLISH событие

**Возврат:** `success: bool`

---

#### `SetOnline(SetOnlineRequest) → SetOnlineResponse`
Обновление online-статуса устройства. Вызывается оркестратором при каждом запросе (с debounce 30s).

**Вход:** `user_id`, `device_id`

**Логика:** SET Redis `online:{user_id}:{device_id}` TTL=60s

**Возврат:** `success: bool`

---

### Internal-методы

#### `GetConversationMembers(internal) → GetConversationMembersResponse`
Массовое получение активных участников чата без проверки приватности.
**Кто вызывает:** notifications-service для рассылки push-уведомлений.
**Возврат:** `members[] { user_id, role }`

---

### `HealthCheck(HealthCheckRequest) → HealthCheckResponse`
Проверяет PostgreSQL (`SELECT 1`), Redis (PING), media-service (PING).

**Возврат:** `status` (`ok`/`degraded`), `db_status`, `redis_status`, `media_service_status`, `version`

---

## Redis ключи

| Ключ | TTL | Назначение |
|------|-----|------------|
| `online:{user_id}:{device_id}` | 60s | Online-статус устройства |
| `typing:{conv_id}:{user_id}` | 5s | Индикатор набора |
| `unread:{user_id}:{conv_id}` | — | Кеш счётчика непрочитанных |
| `conv:{conv_id}` | — | Redis Pub/Sub канал чата |
| `kp_count:{user_id}:{device_id}` | — | Кеш количества KeyPackage |
