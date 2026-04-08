# VaultMind — Development Status

**Last updated:** 2026-04-08
**Device tested:** Samsung Galaxy S23 Ultra (Snapdragon 8 Gen 2, 12 GB RAM, Android 15)

---

## Current State: Core Pipeline Working

The full RAG pipeline (auth → ingest → embed → store → retrieve → LLM) runs on-device. The app is functional, builds successfully, and the main remaining work is around model-loading UX and a few missing chat refinements.

---

## What Works

### Authentication & Security
- Biometric/PIN auth via `BiometricPrompt` (BIOMETRIC_STRONG + device credential fallback)
- Hardware-backed master key in Android Keystore (StrongBox → TEE fallback)
- HKDF-SHA256 per-vault key derivation from master secret
- Auto-lock on app background (immediate / 30s / 1min, configurable)
- Models unloaded and keys wiped from RAM on lock
- FLAG_SECURE on all windows

### Vault Management
- Create / rename / delete vaults
- Multi-vault support with independent encryption keys
- Vault metadata (chunk count, size) displayed on home screen
- Vault list updated on import completion

### Ingestion — On-Device
- .txt file import via SAF file picker (file never copied unencrypted)
- Configurable text chunker from the import screen: 64 / 128 / 256 / 512 token targets with matched overlap
- Current default on-device import setting: 128-token target, 20-token overlap
- **SentencePiece tokenizer** (pure-Kotlin, parses `tokenizer.model` from assets — no native libs)
- EmbeddingGemma 300M inference via LiteRT (`org.tensorflow.lite.Interpreter`)
- Embedding model loaded via memory-mapped file (no OOM on 183 MB model)
- Embedding prompt: `"title: none | text: {chunk}"` (per EmbeddingGemma spec)
- Two-phase ingestion: embed all chunks first, then batch-insert in single transaction
- Embedding dimension validated against vault's stored dim before insert
- 768-dim float32 vectors stored as little-endian BLOBs in SQLCipher

### Ingestion — PC Import
- `.rvault` package import (AES-256-GCM, PBKDF2-SHA256 with 300K iterations)
- Embedding dim validation against vault configuration

### LLM Loading
- Gemma 4 E4B via LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android:0.10.0`)
- **SAF workaround:** model is copied to `context.filesDir` on first load (scoped storage blocks native code from accessing SAF paths); subsequent loads use cached copy
- GPU backend (`Backend.GPU()`)
- Temperature configurable (default 0.3 for factual RAG)

### Settings
- Top-K retrieval (1–15, default 5)
- Temperature slider (0.0–1.0)
- Thinking mode toggle (Gemma 4 `<|think|>` reasoning step)
- Auto-lock delay
- Model file paths (SAF file pickers with persistable URI permission)

### Navigation & UX
- Auto-redirect to Auth screen when vault is locked
- Chat history cleared on lock
- Progress bar during ingestion with phase labels
- Source citations expandable under each AI response
- Stop generation button during streaming
- Copy response action for completed assistant messages
- Per-vault instructions editor stored in `vault_info`

---

## Known Issues

### LLM First-Load Time
The first time the LLM model path is set in Settings, the 4–5 GB model is copied to internal storage. This takes approximately 30–60 seconds and shows "Loading model…" during that time. Subsequent launches load from the cached copy immediately (~10–15 seconds for Engine.initialize()).

### Eager Model Loading For Empty Vaults
Opening a vault currently attempts to load the configured models even if the vault has no imported sources yet. This is unnecessary for brand-new or still-empty vaults and adds avoidable wait time before import.

### Model Warm-State Is Per Chat Screen, Not Per Unlocked Session
The app unloads the LLM when the chat view model is cleared, so leaving and re-entering a vault can trigger another model load. Keeping the model warm for the whole unlocked session is not implemented yet.

### No Chat Screen Vault Stats
The chat screen still does not show how many chunks are in the active vault. The vault list screen does show this (e.g. "32 chunks · 200 KB").

---

## Tech Stack

| Component | Library / Version | Notes |
|---|---|---|
| Language | Kotlin 2.0.21 | |
| UI | Jetpack Compose + Material 3 | Dark indigo/violet theme |
| DI | Hilt 2.57.2 + KSP | |
| LLM runtime | LiteRT-LM 0.10.0 | `com.google.ai.edge.litertlm:litertlm-android` |
| LLM model | Gemma 4 E4B int4 | `gemma-4-E4B-it.litertlm`, ~4–5 GB |
| Embedding runtime | LiteRT 1.0.1 | `org.tensorflow.lite.Interpreter` |
| Embedding model | EmbeddingGemma 300M | `embeddinggemma-300m.tflite`, ~183 MB |
| Tokenizer | Pure-Kotlin SentencePiece | `SentencePieceTokenizer.kt` + `assets/tokenizer.model` |
| Vector storage | SQLCipher 4.5.4 | Per-vault encrypted SQLite |
| Auth | androidx.biometric 1.2.0-alpha05 | |
| Settings | DataStore Preferences 1.1.1 | |
| Navigation | Navigation Compose 2.8.5 | |
| Serialization | kotlinx.serialization 1.7.3 | For .rvault format |

---

## Architecture

```
BiometricPrompt
    ↓ success
Android Keystore KEK (AES-256, hardware-backed)
    ↓ unwrap
Master secret (32 bytes, RAM only)
    ↓ HKDF-SHA256(vaultId)
Per-vault SQLCipher key
    ↓
VaultDatabase (SQLCipher):  chunks + embeddings + vault_info

Ingestion:
  SAF .txt URI
    → TextChunker (configurable, default import setting 128 tok / 20 overlap)
    → SentencePieceTokenizer → EmbeddingEngine (LiteRT)
    → 768-dim L2-normalised vector
    → SQLCipher BLOB

Query:
  User question
    → SentencePieceTokenizer → EmbeddingEngine
    → VectorSearch (brute-force cosine, <5 ms for 2000 vectors)
    → Top-K chunks
    → PromptBuilder (Gemma 4 chat template)
    → LlmEngine (LiteRT-LM, streaming)
    → ChatScreen (token-by-token display)
```

---

## File Structure (Key Files)

```
app/src/main/kotlin/com/vaultmind/app/
├── auth/
│   ├── AuthViewModel.kt        unloads models + wipes keys on lock()
│   ├── KeystoreManager.kt      StrongBox → TEE KEK, 30s auth window
│   └── AuthManager.kt          BiometricPrompt suspend wrapper
├── crypto/
│   ├── KeyDerivation.kt        HKDF-SHA256 (manual Extract + Expand)
│   └── SecureWipe.kt           fills byte[]/char[]/float[] with zeros
├── vault/
│   ├── VaultRepository.kt      master secret lifecycle, vault DB cache
│   └── VaultDatabase.kt        vector cache, beginBatch/commitBatch
├── ingestion/
│   ├── SentencePieceTokenizer.kt  pure-Kotlin protobuf parser + Viterbi
│   ├── EmbeddingEngine.kt      suspend load(), MappedByteBuffer, PFD-alive
│   ├── OnDeviceIngestion.kt    two-phase embed→batch-insert
│   └── ImportViewModel.kt      ensureEmbeddingModelLoaded() before import
├── rag/
│   ├── LlmEngine.kt            copies model to filesDir on first SAF load
│   ├── ChatViewModel.kt        separate embedding/LLM error handling
│   ├── PromptBuilder.kt        Gemma 4 template, thinking mode
│   └── VectorSearch.kt         returns 0f on dimension mismatch
└── settings/
    └── AppPreferences.kt       topK, temperature, thinkingMode, model paths

app/src/main/assets/
└── tokenizer.model             SentencePiece model (4.7 MB)
```

---

## Model Setup (on device)

1. Download `embeddinggemma-300m.tflite` from `huggingface.co/litert-community/embeddinggemma-300m`
2. Download `gemma-4-E4B-it.litertlm` from `huggingface.co/litert-community/gemma-4-E4B-it-litert-lm`
3. Place both in the phone's Downloads folder
4. Open VaultMind → Settings → locate each file
5. On first LLM load from the SAF-selected path: wait 30–60s for model copy to internal storage
6. Subsequent opens load from cache (~10–15 s)

---

## Build

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home \
  ./gradlew assembleDebug --no-configuration-cache

adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Current repository state: `:app:assembleDebug` succeeds in the checked-in project.

---

## Remaining Work

| Priority | Item |
|---|---|
| High | Avoid loading the LLM for empty vaults |
| High | Keep the LLM warm across vault navigation while the app remains unlocked |
| Medium | Show chunk count in chat screen |
| Medium | Add loading indicator for LLM model copy progress |
| Medium | Add optional non-RAG chatbot mode |
| Low | Root detection warning |
| Low | Memory wipe audit |
| Low | Phase 4 stretch goals (timeline mode, summary mode, vault backup) |
