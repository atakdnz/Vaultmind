# VaultMind Codebase Analysis

Date: 2026-04-05
Scope: implementation reviewed against `implementation-plan.md` and the current-status summary the coding agent provided.
Method: read-only source inspection. I did not modify implementation files.

## High-Level Alignment

The codebase broadly matches the intended architecture:

- Offline-first manifest posture is in place: no `INTERNET` permission, backups disabled, and `FLAG_SECURE` is applied.
- The auth/keystore/vault layering exists and uses a reasonable structure: `BiometricPrompt` -> keystore KEK -> master secret -> HKDF -> SQLCipher per vault.
- Multi-vault CRUD, on-device ingestion, PC import, brute-force vector search, and chat UI are all present.
- The app is already much closer to Phase 3 than a bare MVP.

That said, a few implementation details are currently serious enough to affect either correctness or the security model promised by the plan.

## Critical Issues

### 1. Auto-lock does not fully re-gate the UI after background lock

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/MainActivity.kt:64-88`
- `app/src/main/kotlin/com/vaultmind/app/auth/AuthViewModel.kt:107-112`
- `app/src/main/kotlin/com/vaultmind/app/navigation/NavGraph.kt:29-35`
- `app/src/main/kotlin/com/vaultmind/app/rag/ChatViewModel.kt:42-43`
- `app/src/main/kotlin/com/vaultmind/app/rag/ChatViewModel.kt:212-215`

What happens now:

- Backgrounding the app schedules `authViewModel.lock()`.
- Locking wipes the repository state, but navigation is not driven back to the auth screen.
- `NavGraph` collects `isUnlocked` but never uses it to redirect.
- `ChatViewModel` retains in-memory messages until the view model is cleared.
- `LlmEngine` unloads only in `ChatViewModel.onCleared()`, not on lock/background.

Why this matters:

- The plan says nothing should be accessible until authenticated.
- After an auto-lock, the user can return to the existing screen stack instead of being forced through the auth gate.
- Prior chat content can remain visible in-memory on the chat screen.
- The model can stay loaded after the app is supposedly "locked."

This is the most important security mismatch I found beyond the tokenizer placeholder.

### 2. Embedding correctness is still blocked by the placeholder tokenizer

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/ingestion/EmbeddingEngine.kt:30-32`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/EmbeddingEngine.kt:98-105`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/EmbeddingEngine.kt:125-136`

What happens now:

- The embedding model is fed Unicode code points instead of the SentencePiece token IDs the model expects.

Why this matters:

- This is not a quality tweak. It undermines retrieval correctness.
- On-device ingestion is affected.
- Query embeddings are also affected, so even `.rvault` packages built correctly on PC will still be searched using bad query vectors on device.

Until this is replaced with the real tokenizer from the EmbeddingGemma bundle, the RAG pipeline is not trustworthy.

### 3. Embedding dimension mismatches are silently accepted

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/vault/VaultRepository.kt:70-76`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/OnDeviceIngestion.kt:82-107`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/PackageImporter.kt:45-52`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/PackageImporter.kt:130-148`
- `app/src/main/kotlin/com/vaultmind/app/rag/VectorSearch.kt:79-83`

What happens now:

- Vault metadata stores an intended embedding dimension.
- On-device ingestion does not verify that the loaded embedding model matches the vault's configured dimension.
- PC import parses `embedding_dim` from the package but does not validate it against the target vault.
- Vector search uses `minOf(a.size, b.size)` in the dot product, so mismatched vectors are silently truncated instead of rejected.

Why this matters:

- A 256-dim vault, a 768-dim imported package, or a mismatched embedding model can all produce invalid similarity scores without any clear error.
- This is a correctness bug that will look like "poor retrieval quality" instead of failing loudly.

This issue was not listed in the coding agent's current-status summary, but it should be treated as high priority.

## Medium Issues

### 4. Settings are only partially wired into live chat behavior

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/settings/AppPreferences.kt:24-30`
- `app/src/main/kotlin/com/vaultmind/app/settings/SettingsScreen.kt:120-148`
- `app/src/main/kotlin/com/vaultmind/app/rag/ChatViewModel.kt:52-58`
- `app/src/main/kotlin/com/vaultmind/app/rag/ChatViewModel.kt:66-77`
- `app/src/main/kotlin/com/vaultmind/app/rag/ChatViewModel.kt:117-130`
- `app/src/main/kotlin/com/vaultmind/app/rag/LlmEngine.kt:77-96`

Observed behavior:

- `topK` and `thinkingMode` are exposed in settings storage and UI, but `ChatViewModel` does not initialize them from `AppPreferences`.
- Temperature is only applied when the model is loaded. Changing it later does not affect generation until reload.

Impact:

- The app surface suggests these RAG parameters are configurable, but runtime behavior is still mostly hardcoded.

### 5. `PromptBuilder` can underflow when truncating the last context chunk

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/rag/PromptBuilder.kt:82-86`

Observed behavior:

- The truncation path computes `body.take(maxChars - header.length - footer.length - 10)`.
- When the remaining token budget is very small, this value can go negative.

Impact:

- Near the context limit, prompt assembly can fail instead of truncating safely.

### 6. SAF model-path resolution is narrower than the UI implies

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/ingestion/EmbeddingEngine.kt:148-171`
- `app/src/main/kotlin/com/vaultmind/app/rag/LlmEngine.kt:173-217`

Observed behavior:

- Only direct paths, `raw:` document IDs, and `primary:` IDs are handled.
- Common Downloads-provider IDs are not resolved.

Impact:

- A user can successfully pick a model file in Settings and still fail to load it.
- This is especially likely on newer Android document providers.

### 7. `.rvault` password handling still uses immutable `String` state in the UI

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/ingestion/ImportScreen.kt:52-55`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/ImportScreen.kt:96-101`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/ImportScreen.kt:183-189`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/PackageImporter.kt:96-100`

Observed behavior:

- The importer eventually receives a `CharArray` and wipes it.
- But the UI stores the password as a Compose `String` first.

Impact:

- This does not meet the plan's stated `char[]`-over-`String` password handling goal.
- The actual password still lives in immutable UI state longer than intended.

## Performance and Reliability Improvements

### 8. Ingestion/import write path is chunk-by-chunk transactional

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/vault/VaultDatabase.kt:118-146`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/OnDeviceIngestion.kt:89-108`
- `app/src/main/kotlin/com/vaultmind/app/ingestion/PackageImporter.kt:133-143`

Observed behavior:

- Every chunk insert starts and ends its own SQLCipher transaction.

Impact:

- More fsync overhead than necessary.
- Partial imports are possible if a failure occurs mid-run.
- Retrying an interrupted import can duplicate content.

Recommended direction:

- Batch the full import/ingest into a single outer transaction or reasonably sized transaction batches.

### 9. Query path reloads every vector from SQLite on every message

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/rag/VectorSearch.kt:43-44`
- `app/src/main/kotlin/com/vaultmind/app/vault/VaultDatabase.kt:153-163`

Observed behavior:

- Vectors are loaded from the DB for every search instead of being cached on vault open.

Impact:

- It works at current scale, but it does not match the plan's intended in-memory search path.
- This will become noticeable as vaults grow or as the user chats repeatedly.

Recommended direction:

- Cache decoded vectors per open vault and invalidate on ingestion/import.

### 10. Chunking fidelity is still approximate and strips some original separators

Relevant code:

- `app/src/main/kotlin/com/vaultmind/app/ingestion/TextChunker.kt:58-80`
- `scripts/vault_builder.py:62-80`

Observed behavior:

- Chunking uses a 1-token≈4-chars heuristic instead of the real tokenizer.
- Recursive splitting discards the matched separators, so some punctuation/newline structure is not preserved exactly.

Impact:

- Less stable chunk boundaries.
- Lower embedding quality than the plan is aiming for.

Recommended direction:

- Move both Android and PC chunking to the real SentencePiece tokenizer and preserve separator semantics during recursive splitting.

## Plan Gaps Already Called Out by the Coding Agent

These are real gaps, but they were already identified in the status summary:

- `.rvault` file is not deleted after import.
- PBKDF2 is used instead of the planned Argon2id.
- Auth cooldown after repeated failures is not implemented.
- Root detection warning is not implemented.
- Memory-wipe coverage has not been audited end-to-end.
- Chunk metadata prefix is not added.

I agree with that list. I would rank the tokenizer issue and the lock-state/UI enforcement issue above the rest.

## Overall Assessment

The project is structurally on-plan and materially implemented, not just scaffolded.

The main blockers before I would trust it as "secure local RAG" are:

1. Real embedding tokenization.
2. Correct lock-state enforcement across navigation/UI/model lifecycle.
3. Hard validation of embedding dimensions and package metadata.

After those, the next biggest gains are transactional ingestion, vector caching, and fully wiring settings into live RAG behavior.
