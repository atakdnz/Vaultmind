#!/usr/bin/env python3
"""
vault_builder.py — PC-side vault package builder for VaultMind.

Reads a .txt file, chunks it, embeds it with EmbeddingGemma 300M,
and outputs an AES-256-GCM encrypted .rvault package.

Usage:
    python vault_builder.py --input notes.txt --output my_vault.rvault --password mypassword

    # With custom parameters:
    python vault_builder.py --input notes.txt --output my_vault.rvault \\
        --password mypassword --chunk-size 256 --chunk-overlap 40 --embedding-dim 768

Transfer the .rvault file to your phone via USB, Nearby Share, or ADB push,
then import it in VaultMind using "Import from PC".

Package format:
    [32 bytes: PBKDF2-SHA256 salt]
    [12 bytes: AES-GCM nonce]
    [N bytes:  AES-GCM ciphertext of JSON payload + 16-byte auth tag]

NOTE: The Android app uses PBKDF2-HMAC-SHA256 for key derivation (300k iterations)
to avoid a JNI dependency. The original plan specified Argon2id — you can re-enable
Argon2id here if you also add an Argon2id JNI library to the Android app.
"""

import argparse
import json
import os
import struct
import sys
from datetime import datetime, timezone

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
except ImportError:
    print("ERROR: cryptography package not found.")
    print("Install with: pip install cryptography")
    sys.exit(1)

try:
    from sentence_transformers import SentenceTransformer
except ImportError:
    print("ERROR: sentence-transformers package not found.")
    print("Install with: pip install sentence-transformers")
    sys.exit(1)


# ─── Chunking ────────────────────────────────────────────────────────────────

SEPARATORS = ["\n\n", "\n", ". ", "! ", "? ", " "]


def estimate_tokens(text: str) -> int:
    """Approximate token count: 1 token ≈ 4 characters."""
    return max(1, len(text) // 4)


def split_recursive(text: str, max_chars: int, separators: list[str]) -> list[str]:
    """Recursively split text until each piece is ≤ max_chars."""
    if len(text) <= max_chars:
        return [text]
    if not separators:
        return [text[i:i+max_chars] for i in range(0, len(text), max_chars)]

    sep, remaining = separators[0], separators[1:]
    parts = text.split(sep)
    result = []
    for part in parts:
        part = part.strip()
        if not part:
            continue
        if len(part) <= max_chars:
            result.append(part)
        else:
            result.extend(split_recursive(part, max_chars, remaining))
    return result


def chunk_text(text: str, target_tokens: int = 256, overlap_tokens: int = 40) -> list[dict]:
    """
    Split text into overlapping chunks.
    Returns list of dicts: {index, content, token_count}.
    """
    target_chars = target_tokens * 4
    overlap_chars = overlap_tokens * 4

    text = text.replace("\r\n", "\n").replace("\r", "\n").strip()
    if not text:
        return []

    pieces = split_recursive(text, target_chars, SEPARATORS)
    pieces = [p.strip() for p in pieces if p.strip()]

    # Merge small pieces
    merged = []
    current = ""
    for piece in pieces:
        if not current:
            current = piece
        elif len(current) + len(piece) + 2 <= target_chars:
            current += "\n\n" + piece
        else:
            merged.append(current)
            current = piece
    if current:
        merged.append(current)

    # Add overlap
    result = []
    for i, chunk in enumerate(merged):
        if i > 0 and overlap_chars > 0:
            prev = merged[i - 1]
            overlap_text = prev[-overlap_chars:]
            space_idx = overlap_text.find(" ")
            if space_idx > 0:
                overlap_text = overlap_text[space_idx + 1:]
            if overlap_text.strip():
                chunk = overlap_text.strip() + "\n\n" + chunk
        result.append({
            "index": i,
            "content": chunk,
            "token_count": estimate_tokens(chunk)
        })

    return result


# ─── Embedding ───────────────────────────────────────────────────────────────

DOCUMENT_PROMPT = "task: search result | text: "


def load_embedding_model(dim: int = 768):
    """Load EmbeddingGemma 300M via sentence-transformers."""
    model_name = "google/embeddinggemma-300m" if dim == 768 else "google/embeddinggemma-300m-256"
    print(f"Loading embedding model: {model_name}")
    print("(This may take a minute on first run — downloading ~300MB model)")
    return SentenceTransformer(model_name)


def embed_chunks(model, chunks: list[dict]) -> list[dict]:
    """Embed all chunks and add vectors to the chunk dicts."""
    texts = [DOCUMENT_PROMPT + chunk["content"] for chunk in chunks]

    print(f"Embedding {len(texts)} chunks…")
    vectors = model.encode(
        texts,
        batch_size=32,
        show_progress_bar=True,
        normalize_embeddings=True  # L2 normalise for cosine similarity
    )

    for chunk, vector in zip(chunks, vectors):
        chunk["vector"] = vector.tolist()

    return chunks


# ─── Encryption ──────────────────────────────────────────────────────────────

PBKDF2_ITERATIONS = 300_000
SALT_SIZE = 32
NONCE_SIZE = 12


def derive_key_pbkdf2(password: str, salt: bytes) -> bytes:
    """Derive AES-256 key from password using PBKDF2-HMAC-SHA256."""
    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        iterations=PBKDF2_ITERATIONS
    )
    return kdf.derive(password.encode("utf-8"))


def encrypt_package(plaintext: bytes, password: str) -> bytes:
    """
    Encrypt plaintext with AES-256-GCM using a PBKDF2-derived key.

    Returns: salt (32 bytes) || nonce (12 bytes) || ciphertext+tag
    """
    salt = os.urandom(SALT_SIZE)
    key = derive_key_pbkdf2(password, salt)

    nonce = os.urandom(NONCE_SIZE)
    aesgcm = AESGCM(key)
    ciphertext_and_tag = aesgcm.encrypt(nonce, plaintext, None)

    return salt + nonce + ciphertext_and_tag


# ─── Main ─────────────────────────────────────────────────────────────────────

def build_vault(
    input_path: str,
    output_path: str,
    password: str,
    chunk_size: int = 256,
    chunk_overlap: int = 40,
    embedding_dim: int = 768
) -> None:
    print(f"\nVaultMind Vault Builder")
    print(f"  Input:        {input_path}")
    print(f"  Output:       {output_path}")
    print(f"  Chunk size:   {chunk_size} tokens")
    print(f"  Overlap:      {chunk_overlap} tokens")
    print(f"  Embedding:    {embedding_dim}d EmbeddingGemma 300M")
    print()

    # Read input
    print("Reading input file…")
    with open(input_path, "r", encoding="utf-8") as f:
        text = f.read()
    print(f"  {len(text):,} characters read")

    # Chunk
    print("Chunking text…")
    chunks = chunk_text(text, chunk_size, chunk_overlap)
    print(f"  {len(chunks)} chunks produced")
    if not chunks:
        print("ERROR: No chunks produced. Is the file empty?")
        sys.exit(1)

    # Embed
    model = load_embedding_model(embedding_dim)
    chunks = embed_chunks(model, chunks)

    # Build payload
    payload = {
        "version": 1,
        "embedding_model": "embeddinggemma-300m",
        "embedding_dim": embedding_dim,
        "chunk_size": chunk_size,
        "chunk_overlap": chunk_overlap,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "chunks": chunks
    }
    payload_json = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    print(f"\nPayload size: {len(payload_json) / 1024 / 1024:.1f} MB (before encryption)")

    # Encrypt
    print("Encrypting… (deriving key with PBKDF2, 300k iterations — this takes ~2 seconds)")
    encrypted = encrypt_package(payload_json, password)

    # Write output
    with open(output_path, "wb") as f:
        f.write(encrypted)

    size_mb = os.path.getsize(output_path) / 1024 / 1024
    print(f"\nDone! .rvault package written: {output_path} ({size_mb:.1f} MB)")
    print()
    print("Transfer to your phone via USB, Nearby Share, or ADB push.")
    print("Then import in VaultMind: select vault → Add → Import .rvault File")
    print()
    print("SECURITY: Delete this .rvault file from your computer once imported.")


def main():
    parser = argparse.ArgumentParser(
        description="Build an encrypted VaultMind vault package from a text file."
    )
    parser.add_argument("--input", "-i", required=True, help="Input .txt file path")
    parser.add_argument("--output", "-o", required=True, help="Output .rvault file path")
    parser.add_argument("--password", "-p", required=True, help="Encryption password")
    parser.add_argument("--chunk-size", type=int, default=256, help="Tokens per chunk (default: 256)")
    parser.add_argument("--chunk-overlap", type=int, default=40, help="Overlap tokens (default: 40)")
    parser.add_argument(
        "--embedding-dim", type=int, default=768, choices=[256, 768],
        help="Embedding dimension: 768 (full) or 256 (fast) (default: 768)"
    )
    args = parser.parse_args()

    if not os.path.isfile(args.input):
        print(f"ERROR: Input file not found: {args.input}")
        sys.exit(1)

    build_vault(
        input_path=args.input,
        output_path=args.output,
        password=args.password,
        chunk_size=args.chunk_size,
        chunk_overlap=args.chunk_overlap,
        embedding_dim=args.embedding_dim
    )


if __name__ == "__main__":
    main()
