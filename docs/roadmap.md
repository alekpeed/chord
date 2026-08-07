# Roadmap

Milestones follow the product specification. The development rule that orders them: **a weaker
measurable pipeline is preferable to an impressive model that cannot be tested, resumed, corrected
or replaced.** Model integration does not begin until playback, projects, correction and evaluation
are stable.

## Milestone 0 — Repository and architecture ✅

Gradle project, module boundaries, dependency injection, navigation shell, CI, static analysis, test
conventions, decision records.

*Exit criteria met:* clean clone builds, unit tests run, no web framework dependencies.

## Milestone 1 — Local library and media playback ✅ (device verification outstanding)

Storage Access Framework import, project creation, Room database, Media3 playback, background media
session, library screen, deletion.

*Done:* import with reference or managed-copy storage, duplicate detection by checksum, missing and
permission-lost states, playback controller and media session, library search and delete.

*Outstanding:* waveform generation; source relinking has repository support but no UI flow; every
device-dependent path — real Storage Access Framework grants, decoding, background session,
notification controls — is written but unverified on hardware.

## Milestone 2 — Tablet performance view ✅ (partly)

*Done:* synchronized table, current-row tracking with disableable auto-scroll, portrait and landscape
column priority, loop, speed, display transposition, hand-entered chart data, chord editor.

*Outstanding:* count-in and metronome — the `Metronome` interface exists but the low-latency
implementation is unvalidated and deliberately unwritten. Audio transposition is also unresolved:
the current transpose control changes the chord symbols shown, not the sounding pitch.

## Milestone 3 — Processing framework

Stage DAG, progress UI, foreground execution, WorkManager integration, cancellation, retry,
checkpoint persistence, artifact registry, model registry and download manager.

*Exit criteria:* simulated long jobs survive process death; completed stages are not repeated;
pause and cancel are visible and reliable.

## Milestone 4 — Baseline audio analysis

Beat and tempo integration, broad stem separation, baseline chord recognition, vocals excluded from
chord analysis, confidence storage, generated chart rows.

*Exit criteria:* one local file completes end to end; output is editable; raw model output is preserved.

## Milestone 5 — Detailed stems and note analysis

Bass, acoustic piano, electric piano, guitar, remaining target stems, note transcription, bass line,
voicing panel, octave display.

*Exit criteria:* unavailable or experimental stems are clearly labelled; acoustic and electric piano
are separate in both the model and the UI.

## Milestone 6 — Advanced correction

Waveform editor, beat correction, chord-boundary editing, section editing, alternate chord selection,
revision history UI, selective reprocessing.

## Milestone 7 — Ear training

Eligibility engine, chord quality, root, bass and inversion, missing chord and voicing-note
questions, isolated and full-mix toggle, session history.

*Exit criteria:* no low-confidence unconfirmed item is presented as fact; every result links back to
its source event; sessions work offline.

## Milestone 8 — Library intelligence and export

Chord and metadata search, duplicate detection, saved loops, chart export, stem export, JSON project
export, project archive, storage management.

## Milestone 9 — Quality and optimization

Macrobenchmarking, memory profiling, thermal testing, large-project testing, model-pack optimization,
accessibility audit, device matrix, crash-recovery validation.
