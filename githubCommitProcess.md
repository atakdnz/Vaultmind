# GitHub Commit Process

**Last Updated:** 2025-12-25

## Git Config
```bash
git config --global user.name "Atakan Akdeniz"
git config --global user.email "at.akdnz@gmail.com"
```

## Commit & Push
```bash
git add .
git commit -m "Brief summary

- Detail 1
- Detail 2"
git push origin main
```

## ⚠️ IMPORTANT: Always Push to GitHub

**After EVERY commit, you MUST push to origin:**
```bash
git push origin main
```

Commits should NOT remain local-only. The user expects all changes to be pushed to GitHub immediately after committing.

## Rules
- Use present tense ("Add feature" not "Added feature")
- Keep first line concise
- No AI/Claude references in commits
- No emojis
- **Always push after committing** - never leave commits local-only
