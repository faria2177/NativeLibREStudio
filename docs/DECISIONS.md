# Decisions

1. **Pure-Kotlin ELF parser instead of a native one (Phase 3).** Parsing is I/O bound; a JVM
   implementation is memory-safe against malformed input, unit-testable on CI without an emulator,
   and fast enough with memory-mapped windows. Native code (NDK/CMake) will be introduced in Phase 5
   for Capstone, where it is genuinely needed.
2. **Engines are separate JVM modules; Android features are packages inside `app` for now.** Splitting
   UI features into Android library modules is deferred until CI is green, to avoid build breakage
   without an on-device build loop. Interfaces between engine and UI are the `core:*` public APIs.
3. **Imported files are copied into app-private storage.** SAF URIs are not random-access; a private
   read-only copy gives ZipFile/mmap access and guarantees the original is never touched.
4. **History stores summaries, not full results.** Opening an old entry re-runs the deterministic
   extraction on the stored copy, which also re-verifies hashes.
5. **Android packed relocations (APS2) are reported but not yet decoded.** The parser emits an INFO
   warning instead of silently omitting them.
6. **Text-only repository.** Test ELFs are base64 files; the Gradle wrapper JAR is generated and
   committed by CI on the first run.
8. **Self-update via GitHub Releases.** Tag `build-N` ↔ `versionCode N`. The updater is the only code
   that touches the network; analysis stays offline. Debug builds are signed with the release key too so
   either variant can update in place.
7. **Release signing** uses CI secrets when present, otherwise the debug key so artifacts are installable.
