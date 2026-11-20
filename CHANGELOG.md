# Changelog

## 0.3.x — 2026-11-20 (Phases 1–3)

### Added
- Multi-module project: `app`, `core:common`, `core:elf`, `core:archive`; GitHub Actions build, tests, APK artifacts and pre-releases.
- Import via multi-select picker, folder (SAF tree), Share and Open-with intents; streaming copy with progress and cancel.
- Format detection: APK, XAPK, APKS/APKM, AAB, ZIP, JAR, DEX, ELF, unknown.
- Native-library extraction for all Android ABIs incl. nested split APKs and AAB modules, preserving paths; CRC-32 + SHA-256 verification; zip-slip and decompression-bomb protection.
- Set analysis: missing-in-ABI, duplicates, identical-across-ABIs, ABI/machine mismatch, unknown/deprecated ABI, non-standard location, 16 KiB alignment.
- ELF parser: headers, segments, sections, dynamic, symbols (dynsym/symtab, GNU versions), REL/RELA/RELR relocations, notes/Build ID, hardening facts, extended numbering, dynsym recovery without section headers.
- ELF Analyzer UI (9 tabs) with symbol search/filter/sort; extraction report UI with ABI filters and issues.
- TXT/JSON/CSV exports; Room-backed history; dark/light/system theme; first-run authorized-use notice.
- 34 unit tests on real multi-ABI ELF samples (base64-encoded in `core/elf/src/test/resources/elf`).
