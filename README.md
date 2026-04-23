# Memegram

> Защищённый кроссплатформенный мессенджер с end-to-end шифрованием на базе протокола **MLS** (RFC 9420), регистрацией по инвайт-кодам и интеллектуальными функциями обработки контента на устройстве пользователя.

Memegram спроектирован вокруг принципа **Zero-Access**: серверная сторона никогда не видит ни содержимого сообщений, ни приватных ключей. Все криптографические операции выполняются на клиенте, бэкенд работает только с зашифрованными блобами и метаданными, минимально необходимыми для маршрутизации.

Проект разрабатывается в рамках курсового проекта по направлению 09.03.04 «Программная инженерия» НИУ ВШЭ.

---

## Содержание

- [Ключевые свойства](#ключевые-свойства)
- [Возможности](#возможности)
- [Архитектура](#архитектура)
  - [Высокоуровневая схема](#высокоуровневая-схема)
  - [Серверные сервисы](#серверные-сервисы)
  - [Поток сообщений](#поток-сообщений)
- [Технологический стек](#технологический-стек)
- [Структура репозитория](#структура-репозитория)
- [Документация](#документация)
- [Команда](#команда)

---

## Ключевые свойства

- **MLS (RFC 9420)** — групповое сквозное шифрование с прямой и постфактум секретностью; криптоядро на Rust на базе [OpenMLS](https://github.com/openmls/openmls), подключаемое к мобильному клиенту через UniFFI-биндинги.
- **Zero-Access Encryption** — приватные ключи не покидают устройство; на сервере хранятся только зашифрованные `commit`/`welcome`/`application` сообщения и публичные key-package'и.
- **Регистрация по инвайтам** — никаких номеров телефона и email; учётная запись создаётся по одноразовому коду от другого пользователя.
- **Микросервисная архитектура** — 7 доменных gRPC-сервисов и внешний REST-шлюз (оркестратор), каждый со своей базой данных (pattern *database-per-service*).
- **On-device интеллект** — расшифровка голосовых сообщений (Whisper через JNI и `whisper.cpp`) и машинный перевод (NLLB-200 distilled через ONNX Runtime) выполняются прямо на устройстве; содержимое переписки никогда не уходит во внешние сервисы.
- **Кроссплатформенный клиент** — единая кодовая база на Kotlin Multiplatform + Compose Multiplatform со сборками под Android и iOS.

## Возможности

**Для пользователя**

- Регистрация по инвайт-коду, авторизация по криптографическим ключам устройства; поддержка нескольких устройств у одного пользователя.
- Личные диалоги и групповые чаты поверх MLS.
- Отправка текста, изображений, аудио и голосовых сообщений; редактирование и удаление своих сообщений.
- Контакты, избранное, чёрный список; поиск по username.
- Presence (online/offline), индикатор набора текста, отметки о прочтении.
- Push-уведомления (FCM для Android, APNs для iOS) с гибкими настройками — режим тишины, приоритет, отключение по чату.
- On-device расшифровка голосовых сообщений (RU/EN) и перевод текста на язык пользователя.
- Оффлайн-режим: чтение истории и просмотр профилей без сети; локальная БД зашифрована SQLCipher.
- Заявленная функция автоматической фильтрации NSFW-контента в изображениях.

**Для администратора инфраструктуры**

- Развёртывание в Kubernetes (локально через minikube, в проде — на GKE), Kustomize-оверлеи `dev`/`prod`.
- Структурированные логи (`structlog`), мониторинг через Kubernetes-средства, healthcheck-эндпоинты.
- Управление инвайт-кодами через сервис авторизации.

## Архитектура

### Высокоуровневая схема

```
                   ┌─────────────────────────────────────────────────┐
                   │   Mobile client (Kotlin Multiplatform + KMP)    │
                   │   ┌─────────────────────────────────────────┐   │
                   │   │  MLS core (Rust + OpenMLS, UniFFI)      │   │
                   │   │  SQLDelight + SQLCipher  │  Keystore /  │   │
                   │   │  on-device ML (Whisper, NLLB-200)       │   │
                   │   └─────────────────────────────────────────┘   │
                   └────────────────┬────────────────────────────────┘
                                    │ HTTPS (REST + JSON)
                                    ▼
                   ┌─────────────────────────────────────────────────┐
                   │   Orchestrator  (FastAPI, Python 3.12)          │
                   │   AuthN, rate limit, validation, routing        │
                   └────────────────┬────────────────────────────────┘
                                    │ gRPC (Protocol Buffers)
   ┌────────────┬─────────────┬─────┴─────┬──────────────┬────────────┬──────────────┐
   ▼            ▼             ▼           ▼              ▼            ▼              ▼
┌──────┐  ┌──────────┐  ┌──────────┐ ┌──────────┐  ┌──────────┐ ┌──────────┐  ┌────────────┐
│ auth │  │  users   │  │ contacts │ │messaging │  │  media   │ │   item   │  │notifications│
│      │  │          │  │          │ │  (MLS)   │  │          │ │ storage  │  │  (consumer) │
└──┬───┘  └────┬─────┘  └────┬─────┘ └────┬─────┘  └────┬─────┘ └────┬─────┘  └──────┬─────┘
   │           │             │            │             │            │               │
   ▼           ▼             ▼            ▼             ▼            ▼               ▼
┌──────────────────────── PostgreSQL 15 (database-per-service) ─────────────────────────┐
                                            │                                          │
                                            ▼                                          │
                        ┌────────────────────────────────────┐                         │
                        │  Redis 7  (cache, presence,        │◀────────────────────────┘
                        │            Redis Streams events)   │   notifications-cg
                        └────────────────────────────────────┘
                                            │
                                            ▼
                            ┌─────────────────────────────┐
                            │   S3 (encrypted blobs)      │
                            └─────────────────────────────┘
                                            │
                                            ▼
                                   FCM  /  APNs
```

### Серверные сервисы

| Сервис | Транспорт | Порт | Назначение |
|---|---|---|---|
| **orchestrator** | REST (FastAPI) | 8000 | Внешний шлюз: приём запросов клиента, аутентификация, валидация, rate-limit, маршрутизация в gRPC |
| **auth-service** | gRPC | 50051 | Устройства, JWT-сессии, инвайт-коды, публичные ключи |
| **user-service** | gRPC | 50052 | Профили, настройки, аватары |
| **contacts-service** | gRPC | 50053 | Контакты, избранное, чёрный список |
| **messaging-service** | gRPC | 50054 | Диалоги, группы, MLS-состояние (key packages, commit/welcome, эпохи), доставка зашифрованных сообщений |
| **media-service** | gRPC | 50055 | Pre-signed URL для загрузки/скачивания медиа в S3 |
| **item-storage-service** | gRPC | 50056 | Бинарные объекты (аватары и пр.) с SSE-AES256 в S3 |
| **notifications-service** | gRPC | 50057 | Consumer Redis Streams (`notifications-cg`), отправка push через FCM/APNs |

Каждый доменный сервис владеет собственной базой PostgreSQL и не имеет прямого доступа к данным соседей — общение только через gRPC и события Redis Streams.

### Поток сообщений

1. Клиент шифрует `application`-сообщение MLS своим групповым ключом и отправляет в оркестратор по REST.
2. Оркестратор валидирует запрос и через gRPC передаёт зашифрованный блоб в `messaging-service`.
3. `messaging-service` сохраняет сообщение в PostgreSQL и публикует событие в Redis Streams.
4. `notifications-service` (consumer-группа `notifications-cg`) забирает событие, формирует push-нотификации и отправляет их через FCM/APNs устройствам адресатов.
5. Получатели вытягивают новые сообщения и расшифровывают их локально, используя свой MLS-эпоху.

Сервер на всех этапах оперирует только зашифрованными данными и метаданными маршрутизации (id чата, id отправителя, временная метка); содержимое переписки физически недоступно даже оператору сервиса.

## Технологический стек

**Backend** — Python 3.12, FastAPI + Uvicorn (оркестратор), gRPC + grpcio (доменные сервисы), SQLAlchemy 2.0 + asyncpg, Alembic, structlog, aioboto3, firebase-admin, aioapns.

**Хранилища** — PostgreSQL 15 (по одной БД на сервис), Redis 7 (кэш, presence, Streams), AWS S3 (или любое S3-совместимое хранилище для медиа).

**Криптография** — Rust + OpenMLS, ciphersuite `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`, биндинги к Kotlin через UniFFI.

**Клиент** — Kotlin Multiplatform 1.9+, Compose Multiplatform, MVVM + Repository, Koin DI, Kotlin Coroutines/Flow, SQLDelight + SQLCipher, Material 3, Android Keystore (MasterKey AES-256-GCM + EncryptedSharedPreferences), iOS Keychain, Swift-обёртка для iOS-таргета.

**ML on-device** — Whisper (`whisper.cpp` через JNI с API-fallback) для ASR, NLLB-200 distilled (600M) в формате ONNX через ONNX Runtime для перевода, ML Kit Language Identification для определения языка.

**Инфраструктура** — Docker, Docker Compose (для локальной разработки), Kubernetes 1.25+, Kustomize (`base` + оверлеи `dev`/`prod`), GKE Ingress + Google-managed SSL + Cloud Armor в продакшене.

**CI/CD** — GitHub Actions: ruff, black, isort, pytest с матрицей по сервисам и измерением покрытия (pytest-cov); pre-commit hooks.

## Структура репозитория

```
memegram/
├── backend/                    # 8 микросервисов на Python
│   ├── orchestrator/           # FastAPI REST-шлюз
│   ├── auth-service/           # gRPC: устройства, сессии, инвайты
│   ├── user-service/           # gRPC: профили, настройки
│   ├── contacts-service/       # gRPC: контакты, блокировки
│   ├── messaging-service/      # gRPC: чаты, группы, MLS-состояние
│   ├── media-service/          # gRPC: S3 pre-signed URL для медиа
│   ├── item-storage-service/   # gRPC: бинарные объекты
│   ├── notifications-service/  # gRPC + consumer Redis Streams, FCM/APNs
│   └── shared/                 # общие .proto-контракты, утилиты
├── android-ios-app/            # Kotlin Multiplatform клиент
│   ├── composeApp/             # общий код Compose Multiplatform
│   ├── iosApp/                 # iOS-обёртка (Swift, Xcode-проект)
│   └── ...
├── mls-rust/                   # криптоядро MLS на Rust (OpenMLS + UniFFI)
├── ml/
│   └── voice-translator/       # Whisper ASR (JNI + API), NLLB-200
├── k8s/
│   ├── base/                   # Kustomize-манифесты по сервисам
│   └── overlays/
│       ├── dev/                # локальный/dev-кластер
│       └── prod/               # GKE: Ingress + managed cert + Cloud Armor
├── docs/
│   ├── architecture/           # архитектурные решения
│   ├── user_stories_ru.md
│   ├── user_stories_en.md
│   └── memegram-analogues.pdf
├── docker-compose.yml          # 7×PostgreSQL + 4×Redis + сервисы
├── deploy-local.sh             # развёртывание в minikube
├── deploy-gke.sh               # полный цикл деплоя в GKE
└── .github/workflows/ci.yml    # lint + matrix tests
```

## Документация

- Архитектурные решения — [`docs/architecture/`](docs/architecture/) (в частности, мотивация перехода с Kafka/ScyllaDB на Redis Streams + PostgreSQL).
- Пользовательские истории — [`docs/user_stories_ru.md`](docs/user_stories_ru.md), [`docs/user_stories_en.md`](docs/user_stories_en.md).
- Сравнительный анализ аналогов — [`docs/memegram-analogues.pdf`](docs/memegram-analogues.pdf).
- Техническое задание (`tz.txt`) и индивидуальное ТЗ (`tzind.txt`) — в корне репозитория, оформлены под Typst-шаблон по ГОСТ 19.

## Команда

| Участник | Группа | Зона ответственности                                      |
|---|---|-----------------------------------------------------------|
| Павлухин Денис Игоревич | БПИ-236 | Backend-микросервисы, Docker, Kubernetes (Kustomize, GKE) |
| Покровский Александр Андреевич | БПАД-233 | ML-компоненты (ASR, перевод), интеграция on-device моделей |
| Алов Владислав Васильевич | БПАД-233 | Мобильный клиент (KMP/Compose), MLS-интеграция |

---

*Курсовой проект, НИУ ВШЭ, направление 09.03.04 «Программная инженерия», 2025–2026 уч. г.*
