# Working notes for AI sessions

Read first: `PROJECT_STATUS.md`, `docs/DECISIONS.md`, `CHANGELOG.md`.

- Never claim a feature works unless its tests (or a CI build) confirm it. Planned tools stay disabled in the dashboard.
- Engines (`core/*`) must stay pure Kotlin/JVM and fully unit-tested. Run: `./gradlew :core:elf:test :core:archive:test`.
- Imported files are untrusted: bounds-check every offset, never execute or `System.load` them.
- After significant work, update `PROJECT_STATUS.md` and `CHANGELOG.md`.
- Deliver code changes as only the files added/modified.
