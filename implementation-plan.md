# Secure Local RAG Chatbot — Implementation Plan

## Project Overview

A fully offline, encrypted Android app that lets you chat with your personal notes using on-device AI. No data ever leaves the phone. Multi-vault architecture allows separate encrypted knowledge bases.

**Target Device:** Samsung Galaxy S23 Ultra (12GB RAM, Snapdragon 8 Gen 2)
**Platform:** Android only (native Kotlin)

---

## Architecture Summary

```
┌─────────────────────────────────────────────────┐
│                   APP LAYER                      │
│  Jetpack Compose UI + Biometric/PIN Auth Gate    │
├─────────────────────────────────────────────────┤
│                 VAULT MANAGER                    │
│  Create / Delete / Select / Lock Vaults          │
├──────────────────┬──────────────────────────────┤
│  INGESTION       │         QUERY ENGINE          │
│                  │                               │
│  TXT → Chunker → │  User Query                   │
│  EmbeddingGemma  │    → EmbeddingGemma (query)   │
│  → Encrypt →     │    → Cosine Similarity Search │
│  Store in Vault  │    → Top-K Chunks Retrieved   │
│                  │    → Prompt Assembly           │
│  OR              │    → Gemma 4 E4B (LiteRT-LM)  │
│  Import PC Pkg   │    → Streamed Response         │
├──────────────────┴──────────────────────────────┤
│              SECURITY LAYER                      │
│  Android Keystore (HW-backed) + AES-256-GCM     │
│  Per-vault encryption keys                       │
├─────────────────────────────────────────────────┤
│              STORAGE LAYER                       │
│  Encrypted SQLite (SQLCipher) per vault          │
│  Tables: chunks, vectors, metadata               │
└─────────────────────────────────────────────────┘
```

---

## Tech Stack

| Component | Technology | Why |
|---|---|---|
| Language | Kotlin | Best LiteRT-LM support, native Android APIs |
| UI | Jetpack Compose + Material 3 | Modern, declarative, good defaults |
| LLM Runtime | LiteRT-LM (Kotlin API) | Google's official SDK, GPU accelerated, supports Gemma 4 E4B |
| LLM Model | Gemma 4 E4B (int4 quantized) | ~5GB RAM, 128K context, 140+ languages, best on-device model |
| Embedding Model | EmbeddingGemma 300M (int4/int8) | <200MB RAM, 100+ languages, designed for on-device RAG |
| Embedding Runtime | LiteRT (TFLite successor) | Official runtime for EmbeddingGemma on Android |
| Vector Storage | SQLCipher (encrypted SQLite) | Encrypted at rest, no external dependencies, mature |
| Key Management | Android Keystore | Hardware-backed, non-exportable keys |
| Auth | BiometricPrompt API | Fingerprint + PIN fallback |
| DI | Hilt | Standard for Android, reduces boilerplate |
| PC Script | Python + sentence-transformers | For pre-building vault packages on computer |

---

## Core Components

### 1. Authentication Gate

The app opens to a biometric/PIN screen. Nothing is accessible until authenticated.

- Use `BiometricPrompt` with `BIOMETRIC_STRONG` classification
- Fallback to device credential (PIN/pattern/password)
- On success, unlock the master key from Android Keystore
- Auto-lock when app goes to background (configurable: immediate / 30s / 1min)
- After 5 failed attempts: enforce cooldown timer (exponential backoff)

**Key Flow:**
```
App Launch → BiometricPrompt → Success → Derive master key from Keystore
                             → Failure → Retry with PIN fallback
                             → 5 fails → Cooldown
```

### 2. Vault Manager

Each vault is a self-contained, independently encrypted knowledge base.

**Vault data model:**
```kotlin
data class Vault(
    val id: String,           // UUID
    val name: String,         // User-given name (e.g., "Journal", "Work Notes")
    val createdAt: Long,      // Timestamp
    val chunkCount: Int,      // Number of text chunks
    val embeddingDim: Int,    // 256 or 768 (set at creation)
    val sourceMethod: String  // "on_device" or "pc_import"
)
```

**Operations:**
- Create vault (name + choose embedding dimension)
- Delete vault (with confirmation — irreversible, wipes encrypted DB file)
- Rename vault
- View vault stats (chunk count, creation date, storage size)
- Import data into vault (two paths — see Ingestion below)

**Storage:** Each vault gets its own SQLCipher database file:
```
/data/data/com.app.ragvault/databases/
  vault_{uuid1}.db  (encrypted)
  vault_{uuid2}.db  (encrypted)
  master.db         (encrypted — stores vault metadata)
```

Each vault DB has its own encryption key, derived from the master key + vault ID.

### 3. Ingestion Pipeline

#### Path A: On-Device Processing

User picks a `.txt` file → app processes entirely on-phone.

**Steps:**
1. User taps "Add Source" → system file picker (SAF) opens → selects .txt file
2. Read file content into memory (do NOT copy to app storage unencrypted)
3. **Chunk** the text:
   - Use recursive text splitter
   - Target chunk size: 256 tokens (EmbeddingGemma's sweet spot)
   - Overlap: 40 tokens (preserves context across boundaries)
   - Respect paragraph boundaries where possible
   - Prepend vault-specific metadata to each chunk (e.g., chunk index)
4. **Embed** each chunk using EmbeddingGemma 300M via LiteRT:
   - Use document prompt: `"task: search result | text: {chunk}"`
   - Output: 768-dim vector (or 256-dim if user chose smaller for speed)
   - Normalize vectors (L2 normalization for cosine similarity)
   - Show progress bar: "Embedding chunk 45 / 312..."
5. **Encrypt and store** chunks + vectors in vault's SQLCipher DB
6. **Wipe** — clear all plaintext from memory, release file handle
7. Source .txt file is NEVER copied into app storage

**Performance estimate for 500K tokens on S23 Ultra:**
- ~2000 chunks at 256 tokens each
- EmbeddingGemma: ~15ms per chunk → ~30 seconds total
- Chunking + overhead: ~10 seconds
- **Total: ~1-2 minutes** (very manageable)

#### Path B: PC Import

User runs a Python script on their computer, transfers the encrypted package.

**Python Script (`vault_builder.py`):**
```
Input:  notes.txt + password (user-chosen)
Output: vault_package.rvault (encrypted binary)
```

**Script steps:**
1. Read .txt file
2. Chunk with same parameters as on-device (256 tokens, 40 overlap)
3. Embed using `sentence-transformers` with `google/embeddinggemma-300m`
4. Package chunks + vectors + metadata into JSON structure
5. Encrypt with AES-256-GCM using password-derived key (Argon2id KDF)
6. Write `.rvault` file

**App import steps:**
1. User taps "Import from PC" → file picker → selects .rvault file
2. App prompts for the password used during PC export
3. Decrypt package → validate structure
4. Re-encrypt with vault-specific key (Android Keystore derived)
5. Store in vault's SQLCipher DB
6. Delete .rvault from accessible storage, wipe password from memory

**Transfer methods:** USB file transfer, or local WiFi (ADB push, Nearby Share, etc.)

### 4. RAG Query Engine

This is the core — turning a user question into a grounded answer.

**Query flow:**
```
User types question
    ↓
Embed question with EmbeddingGemma
  prompt: "task: search result | query: {question}"
    ↓
Cosine similarity search against vault's vector index
    ↓
Retrieve top-K chunks (K=5 default, configurable 3-10)
    ↓
Assemble prompt for Gemma 4 E4B:
  System: You are a helpful assistant answering questions about the user's
          personal notes. Answer ONLY based on the provided context.
          The notes are in Turkish. Respond in the same language the user
          uses to ask. If the context doesn't contain the answer, say so.

  Context: [chunk 1] [chunk 2] ... [chunk K]

  User: {original question}
    ↓
Send to Gemma 4 E4B via LiteRT-LM (stream tokens)
    ↓
Display streaming response in chat UI
```

**Important parameters:**
- Top-K retrieval: 5 chunks (default), user-adjustable
- Similarity threshold: discard chunks below 0.3 cosine similarity
- Context budget: ~3000 tokens of context max (leaves room for system prompt + generation)
- Temperature: 0.3 (low creativity, high accuracy for factual recall)
- Enable thinking mode: yes (Gemma 4 supports `<|think|>` for step-by-step reasoning — good for timeline/summary queries)

**Special query modes (optional, stretch goals):**
- **Timeline mode:** "Create a timeline of events with [person]" → retrieve many chunks, ask model to order chronologically
- **Summary mode:** "Summarize everything about [topic]" → retrieve top-15 chunks, ask for synthesis
- These need higher K and possibly multiple retrieval passes

### 5. Chat Interface

Simple, functional chat screen.

**Features:**
- Vault selector dropdown at top
- Chat message list (user messages + AI responses)
- Text input + send button
- Streaming token display (word by word)
- "Sources" expandable section under each AI response (shows which chunks were used)
- Clear chat button (clears conversation history, not vault data)
- Settings gear: adjust top-K, temperature, thinking mode on/off

**Conversation memory:**
- Keep last N turns in the prompt (default: 4 turns)
- Conversation is ephemeral — not persisted to disk (security: no chat logs)
- Each new question triggers a fresh retrieval

---

## Security Architecture

### Threat Model & Mitigations

| Threat | Mitigation |
|---|---|
| Phone theft (locked) | Data encrypted at rest with Android Keystore (HW-backed). Undecryptable without biometric/PIN. |
| Phone theft (unlocked) | App auto-locks on background. Biometric required to re-enter. Memory wiped on lock. |
| Malicious app on device | Data in app's private storage (`/data/data/`). SQLCipher encrypted. Other apps cannot read even with storage permission. |
| APK reverse engineering | APK contains no data, no keys, no secrets. Keys generated on-device at first launch. ProGuard/R8 obfuscation. |
| ADB / USB debugging | Release builds: `debuggable=false`. Optionally detect USB debugging and warn user. |
| Root access | Detect root and warn user (not block — their choice). Data still encrypted even on rooted device. |
| Memory dump | Wipe sensitive byte arrays after use. Use `char[]` not `String` for passwords. Minimize plaintext lifetime in RAM. |
| Forensic recovery of deleted .txt | The .txt is read via SAF (content URI), never copied. Instruct user to securely delete original after import. |

### Encryption Details

```
Master Key (Android Keystore)
  ├── Vault 1 Key = HKDF(masterKey, salt=vault1_id)
  │     └── SQLCipher DB encrypted with AES-256-GCM
  ├── Vault 2 Key = HKDF(masterKey, salt=vault2_id)
  │     └── SQLCipher DB encrypted with AES-256-GCM
  └── Master DB Key = HKDF(masterKey, salt="master")
        └── Vault metadata (names, IDs — no content)
```

- **Android Keystore** generates a 256-bit AES key at first launch
- Key is hardware-backed (Strongbox if available on S23 Ultra)
- Key requires user authentication (biometric/PIN) to use
- Key is non-exportable — cannot be extracted even with root
- Per-vault keys derived using HKDF-SHA256 with vault UUID as salt
- SQLCipher uses AES-256 in CBC mode with HMAC-SHA512 page authentication

### Security Checklist for the Coding Agent

- [ ] `android:allowBackup="false"` in AndroidManifest
- [ ] `android:debuggable="false"` in release builds
- [ ] Network permission NOT declared (app is fully offline)
- [ ] No INTERNET permission at all
- [ ] ProGuard/R8 enabled with aggressive obfuscation
- [ ] All sensitive byte arrays zeroed after use
- [ ] No logging of chunk content, queries, or responses in release builds
- [ ] BiometricPrompt with `setNegativButtonText` for fallback
- [ ] `FLAG_SECURE` on all windows (prevents screenshots/screen recording)
- [ ] Auto-lock on `onPause` / `onStop`
- [ ] No content providers exported
- [ ] No broadcast receivers exported

---

## Database Schema (per vault, SQLCipher)

```sql
-- Stored encrypted via SQLCipher
CREATE TABLE chunks (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    content TEXT NOT NULL,           -- The actual text chunk
    chunk_index INTEGER NOT NULL,    -- Position in original document
    token_count INTEGER NOT NULL,    -- Number of tokens in chunk
    created_at INTEGER NOT NULL      -- Unix timestamp
);

CREATE TABLE embeddings (
    chunk_id INTEGER PRIMARY KEY,
    vector BLOB NOT NULL,            -- Float array stored as binary
    dimension INTEGER NOT NULL,      -- 768 or 256
    FOREIGN KEY (chunk_id) REFERENCES chunks(id)
);

CREATE TABLE vault_info (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
-- Stores: source_file_hash, embedding_model_version, chunk_params, etc.
```

**Vector search implementation:** Since we're dealing with ~2000 vectors (not millions), a brute-force cosine similarity scan in memory is perfectly fine. No need for HNSW or FAISS. Load all vectors into a float array on vault open, compute similarities with the query vector, return top-K. This will complete in <10ms for 2000 vectors on the S23 Ultra.

---

## PC Python Script Specification

### `vault_builder.py`

```
Usage: python vault_builder.py --input notes.txt --output my_vault.rvault --password <pass>

Optional:
  --chunk-size 256        (tokens per chunk, default 256)
  --chunk-overlap 40      (overlap tokens, default 40)
  --embedding-dim 768     (768 or 256, default 768)
```

**Dependencies:**
```
sentence-transformers
cryptography (for AES-256-GCM + Argon2id)
sentencepiece
```

**Output format (`.rvault`):**
```
[32 bytes: Argon2id salt]
[12 bytes: AES-GCM nonce]
[N bytes: AES-GCM ciphertext of JSON payload]
[16 bytes: AES-GCM auth tag]
```

**JSON payload structure (before encryption):**
```json
{
  "version": 1,
  "embedding_model": "embeddinggemma-300m",
  "embedding_dim": 768,
  "chunk_size": 256,
  "chunk_overlap": 40,
  "created_at": "2026-04-04T12:00:00Z",
  "chunks": [
    {
      "index": 0,
      "content": "chunk text here...",
      "token_count": 248,
      "vector": [0.0123, -0.0456, ...]
    }
  ]
}
```

---

## Model Files & Storage Budget

| Asset | Size (approx) | Storage Location |
|---|---|---|
| Gemma 4 E4B (int4 GGUF/LiteRT) | ~4-5 GB | App's internal storage or shared model cache |
| EmbeddingGemma 300M (int4) | ~150-200 MB | Bundled with app or downloaded on first launch |
| Vault DB (500K tokens) | ~5-10 MB | App's private encrypted storage |
| **Total** | **~5 GB** | |

**Model download strategy:** Models are large. Two options:
1. **First-launch download** from Hugging Face — simpler, but requires one-time internet
2. **Sideload via USB** — transfer model files manually, app detects them. More secure (no network ever).

Recommendation: **Support both.** Default to sideload, with optional download for convenience. After model is on device, no internet ever needed.

---

## Project Structure

```
app/
├── src/main/kotlin/com/app/ragvault/
│   ├── MainActivity.kt
│   ├── RagVaultApp.kt                    // Hilt application
│   ├── auth/
│   │   ├── AuthScreen.kt                 // Biometric + PIN UI
│   │   ├── AuthManager.kt                // BiometricPrompt logic
│   │   └── KeystoreManager.kt            // Android Keystore operations
│   ├── vault/
│   │   ├── VaultListScreen.kt            // Vault manager UI
│   │   ├── VaultRepository.kt            // CRUD operations
│   │   ├── VaultDatabase.kt              // SQLCipher setup per vault
│   │   └── Vault.kt                      // Data model
│   ├── ingestion/
│   │   ├── ImportScreen.kt               // Import UI (file picker + progress)
│   │   ├── TextChunker.kt                // Recursive text splitter
│   │   ├── EmbeddingEngine.kt            // EmbeddingGemma via LiteRT
│   │   ├── OnDeviceIngestion.kt          // Full on-device pipeline
│   │   └── PackageImporter.kt            // .rvault decryption + import
│   ├── rag/
│   │   ├── ChatScreen.kt                 // Chat UI
│   │   ├── ChatViewModel.kt              // Manages conversation state
│   │   ├── VectorSearch.kt               // Cosine similarity search
│   │   ├── PromptBuilder.kt              // Assembles RAG prompt
│   │   └── LlmEngine.kt                 // Gemma 4 E4B via LiteRT-LM
│   ├── crypto/
│   │   ├── CryptoManager.kt              // AES-256-GCM encrypt/decrypt
│   │   ├── KeyDerivation.kt              // HKDF for vault keys
│   │   └── SecureWipe.kt                 // Memory wiping utilities
│   └── settings/
│       ├── SettingsScreen.kt
│       └── AppPreferences.kt             // Encrypted SharedPreferences
├── build.gradle.kts
└── proguard-rules.pro                    // Obfuscation rules

scripts/
├── vault_builder.py                      // PC-side vault builder
├── requirements.txt
└── README.md                             // Setup instructions
```

---

## Development Phases

### Phase 1: Foundation (MVP)
- Auth gate (biometric + PIN)
- Keystore + encryption setup
- Single vault, on-device ingestion (txt → chunks → embeddings → encrypted DB)
- Basic chat with RAG (Gemma 4 E4B + EmbeddingGemma)
- No PC import yet

### Phase 2: Multi-Vault + PC Import
- Vault manager (create, delete, rename, select)
- Per-vault encryption
- Python script for PC-side vault building
- `.rvault` import flow
- Vault stats display

### Phase 3: Polish + Security Hardening
- Auto-lock behavior
- FLAG_SECURE on all screens
- Root detection warnings
- Source citations in chat responses
- Adjustable RAG parameters (top-K, temperature, thinking mode)
- Memory wiping audit

### Phase 4: Stretch Goals
- Timeline generation mode
- Summary mode (multi-pass retrieval)
- Export chat as encrypted PDF
- Vault backup/restore (encrypted, password-protected)

---

## Key Decisions & Rationale

**Why SQLCipher over ObjectBox?**
ObjectBox has vector search built in, but SQLCipher gives us full control over encryption and is more battle-tested for security-critical apps. With only ~2000 vectors, brute-force search is instant — we don't need HNSW indexing.

**Why not just stuff everything in Gemma's 128K context?**
Your notes are ~500K tokens — 4x the context window. Even if it fit, inference would be extremely slow and expensive on memory. RAG retrieves only the relevant ~1500 tokens, keeping inference fast (~5-10 seconds per response).

**Why EmbeddingGemma specifically?**
It's the only embedding model with an official Android SDK (Google AI Edge RAG Library on Maven), supports 100+ languages including Turkish, runs in <200MB RAM, and shares the same Gemma tokenizer family — meaning better compatibility with the Gemma 4 generation model.

**Why not use the Gemma 4 E4B model itself for embeddings?**
Generation models produce poor embeddings compared to dedicated embedding models. EmbeddingGemma is specifically trained for semantic similarity and retrieval. Using Gemma 4 for embeddings would be slow and inaccurate.

**Why brute-force vector search instead of FAISS/HNSW?**
At 2000 vectors × 768 dimensions, a brute-force cosine similarity scan takes <5ms. FAISS/HNSW add complexity, native library dependencies, and are designed for millions of vectors. Overkill here.

---

## Notes for the Coding Agent

1. **Start with LiteRT-LM Kotlin quickstart.** The official docs are at `ai.google.dev/edge/litert-lm`. The Gallery app source code at `github.com/google-ai-edge/gallery` is the best reference for Gemma 4 integration.

2. **EmbeddingGemma on Android:** Use the LiteRT format from `huggingface.co/litert-community/embeddinggemma-300m`. The Google AI Edge RAG Library SDK is available on Maven — check the sample app linked from that repo.

3. **Tokenizer for chunking:** Use the SentencePiece tokenizer bundled with EmbeddingGemma (not a generic tokenizer). This ensures chunk sizes match what the embedding model expects.

4. **Turkish text handling:** Turkish has special casing rules (dotted/dotless i). Ensure all string operations use `Locale("tr")` to avoid the classic Turkish locale bug.

5. **SQLCipher setup:** Use `net.zetetic:android-database-sqlcipher` with `androidx.sqlite:sqlite` for Room-like API. Pass the derived vault key as the passphrase.

6. **No internet permission.** The AndroidManifest must NOT include `android.permission.INTERNET`. This is both a security feature and a trust signal — the app literally cannot phone home.

7. **Model loading:** Gemma 4 E4B takes ~10-15 seconds to load into memory. Show a loading screen. Keep the model loaded as a singleton while the app is active. Unload on app lock/background to free RAM.

8. **Streaming responses:** LiteRT-LM supports token-by-token streaming. Use Kotlin Flow to pipe tokens to the Compose UI for a responsive chat experience.

9. **ProGuard rules:** Make sure to keep LiteRT-LM and SQLCipher classes from obfuscation. Add appropriate `-keep` rules.

10. **Testing the Turkish RAG quality:** After building, test with queries in both Turkish and English. Gemma 4 handles code-switching well, so the user can ask in either language and get relevant results from Turkish source text.
