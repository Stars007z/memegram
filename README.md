# Memegram

> A secure cross-platform messenger with **MLS (RFC 9420)** end-to-end encryption, invite-code registration, and on-device machine learning — speech-to-text, neural translation, and NSFW content filtering. All intelligence runs locally; your chats never leave the device unencrypted.

---

## Featured Capabilities

- **On-device speech-to-text** — Whisper (`whisper.cpp`, `ggml-small-q5_1.bin` ~500 MB) transcribes voice messages RU/EN, running on a Rust engine bridged to Kotlin/Swift via UniFFI. No cloud dependency.
- **On-device neural translation** — NLLB-200 distilled (600M params, ONNX ~1.2 GB) translates text between 200 languages. Encoder-decoder inference via ONNX Runtime on Android and Swift-native ONNX bridge on iOS.
- **On-device NSFW filtering** — Four specialized ONNX models (~1 GB total) classify and blur explicit imagery: a general classifier, NudeNet body-part detector, anime-content detector, and OWLv2 swastika symbol detector. Runs entirely offline.
- **MLS end-to-end encryption** — Group E2EE via RFC 9420, with forward secrecy and post-compromise security. Crypto core written in Rust on top of OpenMLS. Ciphersuite: `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`.
- **Zero-Access design** — Private keys never leave the device. The server handles only encrypted blobs and routing metadata.
- **Kotlin Multiplatform + Compose Multiplatform** — Single codebase targeting Android and iOS. Shared UI, ViewModels, DI (Koin), persistence (SQLDelight + SQLCipher), and ML service interfaces with platform-specific inference backends.
- **Invite-only registration** — No phone number, no email. Accounts are created via single-use invite codes.

---

## Client Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Kotlin Multiplatform Client                   │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                   commonMain (shared)                      │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐  │  │
│  │  │  Screens +   │  │  Repositories │  │  ML Services      │  │  │
│  │  │  ViewModels  │  │  + API (ktor) │  │  (expect/actual)  │  │  │
│  │  │  (Compose MP)│  │  + SQLDelight │  │  Translation      │  │  │
│  │  │              │  │               │  │  Transcription    │  │  │
│  │  │              │  │               │  │  NSFW Censorship  │  │  │
│  │  └──────┬───────┘  └──────┬────────┘  └────────┬─────────┘  │  │
│  └─────────┼──────────────────┼────────────────────┼───────────┘  │
│            │                  │                    │               │
│  ┌─────────┼──────────────────┼────────────────────┼───────────┐  │
│  │         ▼                  ▼                    ▼           │  │
│  │                 androidMain  │  iosMain                      │  │
│  │  ┌────────────────────────┐ │ ┌────────────────────────┐    │  │
│  │  │ WhisperTranscription   │ │ │ IosWhisperTranscription│    │  │
│  │  │ Service (UniFFI→Rust) │ │ │ Service (UniFFI→Rust)  │    │  │
│  │  ├────────────────────────┤ │ ├────────────────────────┤    │  │
│  │  │ NllbTranslationEngine  │ │ │ IosNllbTranslationEn- │    │  │
│  │  │ (ONNX Runtime Java)    │ │ │ gine (IosOnnxBridge)   │    │  │
│  │  ├────────────────────────┤ │ ├────────────────────────┤    │  │
│  │  │ NsfwCensorEngine       │ │ │ NsfwCensorEngine       │    │  │
│  │  │ (ONNX Runtime Java)    │ │ │ (IosOnnxBridge)        │    │  │
│  │  ├────────────────────────┤ │ ├────────────────────────┤    │  │
│  │  │ ModelManagers          │ │ │ ModelManagers          │    │  │
│  │  │ (download, cache, RAM) │ │ │ (download, cache, RAM) │    │  │
│  │  └────────────────────────┘ │ └────────────────────────┘    │  │
│  │                             │                                │  │
│  │  ┌────────────────────────┐ │ ┌────────────────────────┐    │  │
│  │  │ MlsClientHandle.UniFFI │ │ │ MlsClientHandle.UniFFI │    │  │
│  │  │ ← MLS Crypto (Rust)    │ │ │ ← MLS Crypto (Rust)    │    │  │
│  │  └────────────────────────┘ │ └────────────────────────┘    │  │
│  └──────────────────────────────┴──────────────────────────────┘  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                     Model Gate                             │  │
│  │  Serialized access (PriorityQueue), 60 s idle timeout,    │  │
│  │  memory-pressure handling, release hooks                   │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### Key Technologies

| Layer | Technology |
|---|---|
| UI | Compose Multiplatform, Material 3 |
| State | MVVM, Kotlin Coroutines + Flow |
| DI | Koin |
| Persistence | SQLDelight + SQLCipher (encrypted local DB) |
| Network | Ktor HTTP client, REST/JSON |
| Crypto | MLS core (Rust + OpenMLS → UniFFI), libsodium |
| Key Storage | Android Keystore (AES-256-GCM), iOS Keychain |
| Localization | EnStrings / RuStrings, runtime switch |

### On-Device ML Models

| Model | Format | Size | Engine | Purpose |
|---|---|---|---|---|
| **Whisper Small** | GGML q5_1 | ~500 MB | whisper.cpp (Rust) | Speech-to-text, RU+EN |
| **NLLB-200 600M** | ONNX | ~1.2 GB | ONNX Runtime | Neural MT, 200 languages |
| **NSFW Classifier** | ONNX fp16 | ~620 MB | ONNX Runtime | 6-class image filter |
| **NudeNet** | ONNX | ~12 MB | ONNX Runtime | Body-part localization |
| **Anime Detector** | ONNX | ~45 MB | ONNX Runtime | Anime explicit content |
| **OWLv2 Swastika** | ONNX | ~365 MB | ONNX Runtime | Hate-symbol detection |

Models are downloaded on-demand from a dedicated model CDN and stored locally. RAM checks gate model loading (≥700 MB for Whisper, ≥512 MB for NLLB, ≥1.5 GB for NSFW on Android).

### MLS Crypto Core (`mls-rust/`)

Written in Rust (~530 lines for MLS protocol, ~290 lines for Whisper inference), compiled as a shared library (Android `.so`) and static library (iOS `.a`), with UniFFI generating Kotlin bindings for both platforms. Full MLS lifecycle: key package generation, group creation, member add/remove, message encryption/decryption, epoch management, and state serialization for persistence.

---

## ML Pipeline Detail

### Voice Transcription

```
Voice message (AAC/MP3/PCM)
  → symphonia audio decode
  → rubato resample to 16 kHz mono
  → WhisperEngine (Rust, whisper.cpp)
  → transcribed text (RU/EN auto-detect)
```

### Text Translation

```
Source text
  → ML Kit language identification (Android) / native bridge (iOS)
  → SentencePiece tokenization (NllbTokenizer, 256k vocab)
  → ONNX encoder → decoder autoregressive generation
  → detokenized output → stored alongside original in SQLDelight
```

### NSFW Censorship

```
Image
  → resize to model input size
  → NSFW classifier (6 classes: nudity, gore, alcohol, smoking, military, ok)
  → if flagged: NudeNet body-part detection → Gaussian blur on sensitive regions
  → anime detector parallel pass
  → OWLv2 swastika symbol detection
```

---

## Server Architecture

```
                   HTTPS (REST + JSON)
                           │
                           ▼
          ┌──────────────────────────────┐
          │   Orchestrator (FastAPI)     │
          │   AuthN, rate limit, routing │
          └──────────────┬───────────────┘
                         │ gRPC (Protobuf)
    ┌────────┬───────┬───┴───┬────────┬────────┬────────┐
    ▼        ▼       ▼       ▼        ▼        ▼        ▼
 ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────────────┐
 │ auth │ │users │ │cont- │ │messa-│ │media │ │item  │ │notifications │
 │      │ │      │ │acts  │ │ging  │ │      │ │store │ │(consumer)    │
 └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──────┬───────┘
    │        │        │        │        │        │            │
    ▼        ▼        ▼        ▼        ▼        ▼            ▼
 ┌──────────────── PostgreSQL 15 (database-per-service) ──────────────┐
 │                                                                     │
 │                        Redis 7 (cache, presence, Streams events)    │
 │                                             │                       │
 └─────────────────────────────────────────────┼───────────────────────┘
                                               ▼
                                        S3 (encrypted blobs)
                                               │
                                               ▼
                                        FCM / APNs
```

| Service | Transport | Port | Purpose |
|---|---|---|---|
| Orchestrator | REST (FastAPI) | 8000 | External gateway: auth, validation, rate-limiting, routing |
| Auth | gRPC | 50051 | Devices, JWT sessions, invite codes, public keys |
| Users | gRPC | 50052 | Profiles, settings, avatars |
| Contacts | gRPC | 50053 | Contacts, favorites, block list |
| Messaging | gRPC | 50054 | Chats, groups, MLS state, encrypted message delivery |
| Media | gRPC | 50055 | S3 pre-signed URLs for upload/download |
| Item Storage | gRPC | 50056 | Binary objects (avatars, etc.), SSE-AES256 in S3 |
| Notifications | gRPC | 50057 | Redis Streams consumer, push via FCM/APNs |

---

## Repository Structure

```
memegram/
├── android-ios-app/            # KMP client (Compose Multiplatform)
│   ├── composeApp/             # shared code + platform-specific
│   ├── mls-rust/               # MLS crypto + Whisper engine (Rust)
│   └── memegram-ios/           # iOS Xcode project wrapper
├── ml/
│   └── voice-translator/       # Legacy/test Whisper JNI + API implementation
├── exported_models/
│   └── nllb-200-distilled-600M/ # Static export of NLLB model artifacts
├── backend/                    # 8 Python microservices
│   ├── orchestrator/           # FastAPI REST gateway
│   ├── auth-service/           # gRPC: auth & invites
│   ├── user-service/           # gRPC: profiles
│   ├── contacts-service/       # gRPC: contacts & blocks
│   ├── messaging-service/      # gRPC: chats, groups, MLS state
│   ├── media-service/          # gRPC: S3 pre-signed URLs
│   ├── item-storage-service/   # gRPC: binary blobs
│   ├── notifications-service/  # gRPC + Redis Streams consumer
│   └── shared/                 # shared protobuf contracts, utilities
├── k8s/
│   ├── base/                   # Kustomize manifests per service
│   └── overlays/
│       ├── dev/                # local/dev cluster
│       └── prod/               # GKE: Ingress + managed SSL + Cloud Armor
├── docs/
│   ├── architecture/           # architectural decisions per service
│   ├── user_stories_en.md
│   └── user_stories_ru.md
├── docker-compose.yml          # 7×PostgreSQL + Redis + services
├── deploy-local.sh             # minikube deployment
├── deploy-gke.sh               # GKE deployment
└── .github/workflows/ci.yml    # lint + matrix tests
```

---

## Tech Stack Summary

**Client** — Kotlin Multiplatform 1.9+, Compose Multiplatform 1.10, MVVM + Repository, Koin DI, Coroutines/Flow, SQLDelight + SQLCipher, Material 3, Ktor 3.4, libsodium, Android Keystore, iOS Keychain.

**ML On-Device** — Whisper (`whisper.cpp` via Rust/UniFFI) for ASR, NLLB-200 distilled 600M (ONNX) for translation, 4 specialized ONNX models for NSFW censorship. ONNX Runtime 1.24 on Android, Swift-native ONNX bridge on iOS.

**MLS Crypto** — Rust + OpenMLS, UniFFI bindings, `MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519`.

**Backend** — Python 3.12, FastAPI + Uvicorn, gRPC + grpcio, SQLAlchemy 2.0 + asyncpg, Alembic, structlog, aioboto3, firebase-admin, aioapns.

**Storage** — PostgreSQL 15 (database-per-service), Redis 7 (cache, presence, Streams), S3-compatible storage.

**Infrastructure** — Docker, Docker Compose, Kubernetes 1.25+, Kustomize (`dev`/`prod` overlays), GKE Ingress + Google-managed SSL + Cloud Armor.

**CI/CD** — GitHub Actions: ruff, black, isort, pytest (matrix per service, pytest-cov coverage), pre-commit hooks.

---

## Team

| Member | Group | Responsibility |
|---|---|---|
| Denis Pavlukhin | БПИ-236 | Backend microservices, Docker, Kubernetes (Kustomize, GKE) |
| Alexander Pokrovsky | БПАД-233 | ML components (ASR, translation), on-device model integration |
| Vladislav Alov | БПАД-233 | Mobile client (KMP/Compose), MLS integration |

---

*Course project, HSE University, Data Science and Business analitics, 2025–2026*
