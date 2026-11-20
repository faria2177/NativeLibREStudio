# Project Status

_Last updated: 2026-11-20_

## Phases

| Phase | Status | Notes |
|---|---|---|
| 1 Foundation | ✅ Done | Modules, Compose M3 UI (dark/light), SAF import (files, folder, share, open-with), APK/ZIP parsing |
| 2 Native extraction | ✅ Done | ABI detection, `.so` identification, extraction, CRC-32 + SHA-256 verification, history |
| 3 ELF analysis | ✅ Done | Header, segments, sections, dynamic, symbols, versions, relocations, notes, hardening facts |
| 4 Symbols & strings | ✅ Done | Symbol search/filter/sort + CSV/JSON/TXT export; strings engine (ASCII output verified identical to GNU strings on 3 binaries incl. 123 MB libLLVM) and Strings Explorer |
| 5 Disassembly | ⏳ Pending | Capstone via NDK/JNI — licence (BSD-3) and Android build to be verified first |
| 6 Decompilation | ⏳ Pending | Feasibility assessment of Ghidra/Rizin/RetDec on-device |
| 7 Advanced tools | ⏳ Pending | Dependency tree view, hex editor, comparison, xrefs |
| 8 Security analysis | ⏳ Pending | Header-level hardening facts already shown in ELF Analyzer |
| 9 Optimization | ⏳ Pending | mmap windows + streaming already used; profiling on very large libs pending |
| 10 Release | ⏳ Pending | |

## Test results

Local JVM run: **53 tests, 0 failures**
- `core:strings` — 14 tests (reference comparison on all samples, offsets/sections/vaddr, chunk boundaries, UTF-16 LE/BE alignment, UTF-8/Bengali, invalid UTF-8, limits, cancellation, classification, reports, hex dump)
- `core:elf` — 17 tests (symbol export, 4 ABIs, stripped, versioned, no-section-headers, truncation, corrupted headers, 500 random mutations, determinism, big-endian)
- `core:archive` — 16 tests (APK/XAPK/APKS/AAB, missing/duplicate/mismatch/corrupted, zip-slip, invalid/truncated ZIP, cancellation, reproducible reports)
- `app` — 6 tests (file-name handling, update tag/asset/SHA256SUMS logic)
- Extra local fuzzing: 120,000 mutated ELFs parsed with no unexpected exception.

## Build status

✅ CI run for build-6 (Phase 4) passed: 53 unit tests, debug + release APKs, lint (report-only).
Release [build-6](https://github.com/faria2177/NativeLibREStudio/releases/tag/build-6) is signed with the permanent
release key (EC P-256, cert SHA-256 `70:C0:E4:47:…:1E:89:F2:E4`), verified from the downloaded APK.
Every push to `main` publishes a new `build-N` release that the in-app updater offers.

## Release & updates

- CI signs debug and release APKs with the permanent release key from the four `RELEASE_*` secrets; the workflow validates the keystore first and fails clearly if a secret is wrong.
- In-app updater checks `faria2177/NativeLibREStudio` latest release; first install signed with the new key requires uninstalling builds signed with the old debug key once.

## Performance (desktop JVM reference)

libLLVM-17.so (123 MB): ELF parse 0.35 s; full string scan (3 encodings) + classification ≈ 2 s, 40 MB retained for 200k strings. Phones will be slower; scans show progress and can be cancelled.

## Known limitations / bugs

- UTF-16 detection skips CJK/Hangul blocks and requires ≥75% letters/digits to limit noise from machine code; some ASCII-symbol noise remains (same as `strings -el`).

- Android packed relocations (APS2, `SHT_ANDROID_REL/RELA`) are detected but not decoded.
- Android 14+ `ZipFile` refuses archives containing `..` entry names; such files fail with an "invalid archive" message rather than a per-entry zip-slip report.
- Not yet verified on a physical device (acceptance criterion 13).
- Android UI features are packages in `app`, not separate Gradle modules yet (see docs/DECISIONS.md).

## Next tasks

1. Install build-1 on a real device and test with real APKs (acceptance criterion 13).
2. Phase 5: Capstone evaluation (licence, Android NDK build, maintenance) and NDK/CMake/JNI integration.
