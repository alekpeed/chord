# Milestone 0 and the first vertical slice — acceptance checklist

Covers Milestone 0 in full and the "first coding task" from the specification's agent execution
rules: tablet navigation shell, Room project skeleton, local media import, Media3 playback, a
synchronized table driven by hand-entered chord events, and tests for timeline lookup and process
recreation.

## Milestone 0

- [x] Gradle project with Kotlin and Compose
- [x] Module boundaries enforced by dependency direction
- [x] Dependency injection (Hilt)
- [x] Navigation shell, adaptive rail on tablets and bottom bar on phones
- [x] CI build
- [x] Static analysis (detekt, with ktlint formatting rules)
- [x] Test conventions documented
- [x] Architecture decision records
- [x] Clean clone builds
- [x] Unit tests run
- [x] No web framework dependencies

## First vertical slice

- [x] Storage Access Framework import with persisted URI permission
- [x] Reference and managed-copy storage modes, chosen per import
- [x] Duplicate detection by content checksum
- [x] Room database with projects, media assets, revisions, chart and saved loops
- [x] Schema exported and committed
- [x] Media3 playback with a media session
- [x] Playback controller as the app's single clock
- [x] Library screen with search, empty state, unavailable-source state and deletion
- [x] Synchronized chord table with current-row tracking
- [x] Auto-scroll that can be turned off
- [x] Previous and next measure
- [x] Loop, speed, display transposition, hide chords
- [x] Symbol, Roman numeral and Nashville notation
- [x] Chord correction that forks a revision instead of overwriting
- [x] Timeline lookup tests, including behavior at event boundaries
- [x] Process-recreation test for the performance ViewModel

## Quality gates

- [x] Automated tests — 76 JVM and Robolectric tests
- [x] Error-state UI — missing source, lost permission, unsupported decoder, unknown playback error, empty chart
- [x] Persistence verified — round trips, cascade deletion, revision history
- [x] Accessibility labels — table rows read as one sentence; hidden chords stay hidden from the screen reader
- [x] Documentation matches behavior
- [x] No unfinished placeholder presented as complete

## Deliberately not done

- [ ] Waveform generation and cache — Milestone 1 item, deferred with the analysis work it serves
- [ ] Source relinking UI — repository support exists, no screen flow yet
- [ ] Count-in and metronome — interface only; latency approach unvalidated, see `docs/roadmap.md`
- [ ] Audio transposition — the transpose control changes symbols, not sounding pitch
- [ ] Instrumentation and Compose UI tests in CI — written, not runnable on a runner without KVM

## Not verified on hardware

Nothing device-dependent has been run on a real tablet: Storage Access Framework grants and
revocation, audio decoding per container, background playback with the screen off, notification and
lock-screen controls, and process death during playback. This is the largest outstanding risk in
this milestone.
