# VaultMind

VaultMind is a fully offline, encrypted Android application that acts as a private, on-device Retrieval-Augmented Generation (RAG) system. It allows you to ingest personal notes and documents, and chat with them using powerful, locally running AI models. 

**Absolute Privacy:** No data ever leaves your device. Uniquely, even the *models* run completely offline. If you turn on Airplane Mode, VaultMind works flawlessly.

---

## Key Features

*   **Fully Offline Inference:** Runs Google's Gemma 4 (E4B) Large Language Model and EmbeddingGemma 300M directly on your Android device using LiteRT.
*   **Hardware-Backed Security:** Protects your vaults using Android Keystore (StrongBox TEE). Data is encrypted at rest using SQLCipher (AES-256-GCM). Keys are wiped from memory immediately upon locking the vault.
*   **Multi-Vault Architecture:** Create distinct, isolated workspaces (e.g., "Personal Notes", "Work Docs"). Each vault is encrypted with a unique derived key.
*   **On-Device Ingestion:** Import `.txt` files directly into a vault. Chunk size is configurable at import time (`64 / 128 / 256 / 512` tokens, with matched overlap). The app computes embeddings with a pure-Kotlin SentencePiece tokenizer plus the local embedding model and stores the vectors securely.
*   **PC Import Support:** Need to ingest massive amounts of text? Use the provided Python script (`scripts/vault_builder.py`) to process documents on your PC, generating an AES-256-GCM encrypted `.rvault` package that you can import into the Android app.
*   **Biometric Authentication:** Gate access to the app using robust Biometric Prompt functionality (Fingerprint/Face Unlock + PIN fallback).
*   **Instant Auto-Lock:** Background the app and it locks automatically, dropping encryption keys and unloading LLMs from RAM to prevent unauthorized access.
*   **Empty-Vault Aware Chat:** Opening a brand-new or empty vault does not load the LLM anymore. VaultMind waits until the vault actually has imported sources.
*   **Session-Wide Model Lifetime:** Once loaded, the LLM stays in memory while the app remains open and unlocked, even if you leave a vault and return to the home screen.
*   **Manual Model Controls:** The home screen includes `Load Model` and `Unload` controls so you can warm the runtime before opening a vault or free memory manually.
*   **Vault-Specific Instructions:** Each vault can store its own prompt-style instructions so the assistant has extra context about the corpus and response style.
*   **Streaming Chat UX:** Responses stream token-by-token, can be stopped mid-generation, include expandable source citations, and can be copied from the chat UI.
*   **GPU Acceleration:** Utilizes your device's GPU (via OpenCL) for blazing-fast token generation on supported hardware (with automatic fallback to CPU).

---

## Architecture

VaultMind relies on a pipeline designed specifically for privacy and mobile capabilities:

1.  **Auth Layer:** Validates identity via Biometrics. Unwraps the Master Secret securely stored in Android Keystore.
2.  **Storage Layer:** Each Vault operates its own independent SQLCipher database. The Master Secret leverages HKDF-SHA256 to derive specific, per-vault keys.
3.  **Ingestion:** Text is chunked with a configurable target size and overlap (default on-device import: `128` tokens with `20` token overlap). A custom Kotlin implementation of the SentencePiece tokenizer parses the text. `EmbeddingGemma` (via memory-mapped LiteRT) calculates vectors.
4.  **Retrieval Engine:** Performs lightning-fast brute-force cosine similarity searches across the vault's embedded chunks to find contextually relevant information.
5.  **Generation:** Retrieves top-K chunks, formats the Gemma 4 prompt template (with optional `<|think|>` reasoning steps), and streams the generated response token-by-token directly to the Compose UI.

---

## Setup & Installation (Android)

Because VaultMind runs powerful LLMs locally, you must provide the model files yourself (they are too large to package in an APK).

**Requirements:**
*   A modern Android Device (Tested heavily on Samsung Galaxy S23 Ultra - Snapdragon 8 Gen 2, 12GB RAM).
*   Android 12+ (SDK 31+) is highly recommended.

**Steps:**

1.  **Install the APK:** Download the latest `v1.x.x` release from the GitHub Releases page and install it on your device.
2.  **Download Embedding Model:**
    *   Download `embeddinggemma-300m.tflite` (~183 MB) from [litert-community/embeddinggemma-300m on HuggingFace](https://huggingface.co/litert-community/embeddinggemma-300m).
3.  **Download Language Model:**
    *   Download `gemma-4-E4B-it.litertlm` (~3.6 GB) from [litert-community/gemma-4-E4B-it-litert-lm on HuggingFace](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm).
4.  **Configure VaultMind:**
    *   Place both downloaded files in your phone's `Downloads` folder.
    *   Open VaultMind.
    *   Navigate to **Settings**.
    *   Select the respective files for the Embedding Model and the LLM Model.
5.  **Initial Load:** The first time VaultMind loads the LLM from a SAF-selected path, it securely copies the large `.litertlm` model to isolated internal storage (this bypasses Scoped Storage limitations for native code). This takes 30-60 seconds. Subsequent model loads reuse the cached internal copy, though engine initialization still takes time.

---

## PC Vault Builder Script

For large document collections, you can build `.rvault` packages on your computer.

Requires Python 3 and some dependencies:
```bash
cd scripts
pip install -r requirements.txt
```

Usage:
```bash
python vault_builder.py --input my_notes.txt --output my_knowledge.rvault --password "SuperSecret123!"
```
Transfer `my_knowledge.rvault` to your Android device and use the "Import .rvault File" option in VaultMind. Provide the same password to decrypt and import the vectors.

---

## Technology Stack

*   **Language:** Kotlin (Native Android)
*   **UI Framework:** Jetpack Compose + Material 3
*   **AI Inference (LLM):** Google LiteRT-LM (`com.google.ai.edge.litertlm`)
*   **AI Inference (Embeddings):** Google LiteRT (`org.tensorflow.lite`)
*   **Models:** Gemma 4 E4B (int4) & EmbeddingGemma 300M
*   **Database:** SQLCipher 4.5.4 (Encrypted SQLite)
*   **Dependency Injection:** Dagger Hilt
*   **Key Management:** Android Keystore API (StrongBox)
*   **Authentication:** `androidx.biometric`

---

## Known Limitations
*   Due to the massive size of LLMs, older devices or devices with low RAM (<8GB) may experience extreme lag or silently crash due to Android's Low Memory Killer (LMK).
*   The model is not loaded automatically on app launch or on the home screen by default. It loads when you open a non-empty vault or when you trigger `Load Model` manually from the home screen.

## License
*Private / Internal Project.*
