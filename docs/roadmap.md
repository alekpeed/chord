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

## Milestone 3 — Processing framework ✅ (mostly)

*Done:* jobs and stages persisted as database rows, foreground service with live progress and a Stop
action, per-stage status and messages, cancellation, orphaned-job recovery after process death, a
`ProcessingBackendGateway` interface with a local implementation.

*Outstanding:* stage-level *resume* — a killed job records which stages finished but still restarts
from the beginning. WorkManager for deferred work (model downloads, cleanup, export). Artifact
registry and model download manager, which have nothing to manage until a model exists.

## Milestone 4 — Baseline audio analysis ✅

*Done:* tempo, beat and downbeat tracking, meter estimation, harmonic/percussive separation,
beat-synchronous chord recognition over 22 chord types with Viterbi decoding, key estimation,
section detection, bass pitch tracking, per-chord confidence, and alternates stored alongside the
chosen chord.

*Exit criteria met* against synthesized audio: a file completes end to end, output is editable, and
the machine result is preserved under every correction.

*Outstanding:* accuracy against real recordings is unmeasured, and vocals are reduced by mid-side
processing rather than separated.

## Milestone 5 — Detailed stems and note analysis — not started

Requires deep-learning separation. The ten target stems, note transcription per instrument, the
voicing panel and octave display all wait on that. This is the largest remaining piece of the
product, and per `docs/model-registry.md` no model ships until it is benchmarked on real hardware.

## Milestone 6 — Advanced correction ✅ (partly)

*Done:* alternate chord selection from what the analysis also heard, splitting a region at the
playhead, merging with the next region, moving a boundary, renaming a section, and revision history
with restore — every structural edit forks a user revision exactly as a chord correction does.

*Outstanding:* the waveform editor, beat-grid correction, and selective reprocessing of a single
stage.

## Milestone 7 — Ear training ✅

*Done:* the eligibility engine, all six exercise types, replay, reveal with the confidence at
generation time and a link back to the source bar, per-skill history and a weakest-skill session.

*Outstanding:* isolated-stem playback, which needs stems. The control exists and is disabled with
that reason shown.

## Milestone 8 — Library intelligence and export ✅ (partly)

*Done:* metadata search, duplicate detection by checksum, chord-symbol queries in the database, chart
export as a readable text lead sheet and as structured JSON that keeps chords, confidence and
provenance intact.

*Outstanding:* the export UI, saved-loop UI, stem export, project archive, storage management.

## Milestone 9 — Quality and optimization

Macrobenchmarking, memory profiling, thermal testing, large-project testing, model-pack optimization,
accessibility audit, device matrix, crash-recovery validation.
