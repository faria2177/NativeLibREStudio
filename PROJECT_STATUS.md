# Project Status

_Last updated: 2026-11-20_

## Phases

| Phase | Status | Notes |
|---|---|---|
| 1 Foundation | ✅ Done | Modules, Compose M3 UI (dark/light), SAF import (files, folder, share, open-with), APK/ZIP parsing |
| 2 Native extraction | ✅ Done | ABI detection, `.so` identification, extraction, CRC-32 + SHA-256 verification, history |
| 3 ELF analysis | ✅ Done | Header, segments, sections, dynamic, symbols, versions, relocations, notes, hardening facts |
| 4 Symbols & strings | 🟡 Partial | Symbol search/filter/sort/export done (via ELF report). Strings engine pending |
| 5 Disassembly | ⏳ Pending | Capstone via NDK/JNI — licence (BSD-3) and Android build to be verified first |
| 6 Decompilation | ⏳ Pending | Feasibility assessment of Ghidra/Rizin/RetDec on-device |
| 7 Advanced tools | ⏳ Pending | Dependency tree view, hex editor, comparison, xrefs |
| 8 Security analysis | ⏳ Pending | Header-level hardening facts already shown in ELF Analyzer |
| 9 Optimization | ⏳ Pending | mmap windows + streaming already used; profiling on very large libs pending |
| 10 Release | ⏳ Pending | |

## Test results

Local JVM run (Kotlin 2.0.21, JUnit 4.13.2): **34 tests, 0 failures**
- `core:elf` — 16 tests (4 ABIs, stripped, versioned, no-section-headers, truncation, corrupted headers, 500 random mutations, determinism, big-endian)
- `core:archive` — 16 tests (APK/XAPK/APKS/AAB, missing/duplicate/mismatch/corrupted, zip-slip, invalid/truncated ZIP, cancellation, reproducible reports)
- `app` — 2 tests (file-name handling)
- Extra local fuzzing: 120,000 mutated ELFs parsed with no unexpected exception.

## Build status

See the **Build & Test** workflow in GitHub Actions. Successful pushes to `main` publish a `build-N` pre-release with debug and release APKs.

## Known limitations / bugs

- Android packed relocations (APS2, `SHT_ANDROID_REL/RELA`) are detected but not decoded.
- Android 14+ `ZipFile` refuses archives containing `..` entry names; such files fail with an "invalid archive" message rather than a per-entry zip-slip report.
- Not yet verified on a physical device (acceptance criterion 13).
- Android UI features are packages in `app`, not separate Gradle modules yet (see docs/DECISIONS.md).

## Next tasks

1. Confirm CI green; install the debug APK on a real device and test with real APKs.
2. Phase 4: strings engine (ASCII/UTF-8/UTF-16LE/BE, min length, regex, offsets, TXT/CSV/JSON export, URL/path classification).
3. Phase 5: Capstone evaluation and NDK/CMake integration.
