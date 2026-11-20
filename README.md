# Native Library RE Studio

On-device Android toolkit for extracting and statically analysing native libraries (`.so`) from
Android packages. Everything runs locally; imported files are treated as untrusted and are **never
loaded or executed**.

> Status: Phases 1–3 (foundation, native extraction, ELF analysis). See [PROJECT_STATUS.md](PROJECT_STATUS.md).

## What works today

| Area | Details |
|---|---|
| Import | Multiple-file picker (SAF), folder import, Share / Open-with intents. Inputs are streamed into private storage; originals are never modified. |
| Formats | APK, XAPK, APKS/APKM split sets (nested APKs), AAB modules, ZIP, JAR, direct `.so`/ELF. DEX and raw binaries are identified. |
| Extraction | `lib/<abi>/` detection for arm64-v8a, armeabi-v7a, armeabi, x86, x86_64, riscv64 (+ deprecated mips/mips64), original paths preserved, CRC-32 **and** independent SHA-256 re-read verification. |
| Problems detected | Corrupted entries (CRC/size), non-ELF `.so`, ABI/machine mismatch, libraries missing in some ABIs, duplicates across splits, byte-identical files under different ABIs, non-standard locations, unknown/deprecated ABIs, zip-slip paths, decompression bombs, missing 16 KiB page alignment. |
| ELF analyzer | ELF32/64, LE/BE, header, program headers, sections, dynamic section (NEEDED/SONAME/RPATH/RUNPATH/flags), `.dynsym` + `.symtab`, GNU symbol versions (verdef/verneed/versym), REL/RELA/RELR relocations with per-arch type names, notes and GNU Build ID, hardening facts (RWX, NX stack, RELRO, BIND_NOW, TEXTREL, 16 KiB alignment). Recovers `.dynsym` from `DT_HASH`/`DT_GNU_HASH` when section headers are stripped. Explains which information is unavailable for stripped binaries. |
| Symbol explorer | Search by name/address, filters (exported, imported, functions, objects, weak, global, local, undefined, per table), sort by name/address/size. |
| Export | Extraction report TXT/JSON/CSV, ELF report TXT/JSON, single `.so` or all libraries to a folder (structure preserved). Reports are deterministic. |
| History | Room database; re-opening an entry re-verifies the stored copy. |

Planned (shown as disabled tiles in the app, not faked): strings (Phase 4), disassembly via Capstone
(Phase 5), decompilation (Phase 6), dependency trees / hex editor / comparison (Phase 7), security
indicators (Phase 8).

## Modules

```
app/            Android app (Kotlin, Jetpack Compose, Material 3, Room, SAF)
core/common     ByteSource (bounds-checked, memory-mapped), hashing, deterministic JSON writer
core/elf        ELF parser + reports (pure Kotlin/JVM, unit tested)
core/archive    Format detection, native-library extraction, verification, reports (pure Kotlin/JVM)
tools/          Script + sources that regenerate the ELF test samples
```

## Building

CI (GitHub Actions) runs all unit tests, builds debug + release APKs, uploads them as artifacts and
publishes a pre-release `build-N` on every push to `main`.

Locally / in AndroidIDE: JDK 17, `./gradlew :core:elf:test :core:archive:test :app:assembleDebug`.
The repository is text-only; the first CI run commits `gradle/wrapper/gradle-wrapper.jar`.
If you build before that, run `gradle wrapper --gradle-version 8.11.1` once.

Release signing: add repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Without them the release APK is signed with the debug key.

## Tests

`core/elf` and `core/archive` are tested against real shared objects built with clang 18 + lld for
arm64-v8a, armeabi-v7a, x86 and x86_64 (plus stripped and symbol-versioned variants); expected values
were cross-checked with `llvm-readelf`. Tests also cover section-header-stripped files, truncation,
corrupted headers, random byte mutation, CRC corruption, zip-slip, nested XAPK/APKS, AAB modules and
cancellation.

## Legal

For authorized reverse engineering, interoperability research, security auditing and education on
software you own or may inspect. No functionality for bypassing licensing/DRM, stealing credentials or
modifying third-party apps.
