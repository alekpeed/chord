# Hearsay

A native Android app that turns a recording you own into a permanent, editable harmonic study
project: synchronized chords, a beat and section map, practice controls, and — later — separated
stems, note transcription and ear training built from your own library.

The name is the design brief. Hearsay is testimony you heard but cannot fully verify. That is what
a chord analysis is, and the app is built to say so: every result carries a confidence, alternates
stay reachable, corrections are yours, and the machine's original answer is never overwritten.

**Tablet-first.** The primary interface is a large table that follows the song bar by bar, meant to
be read at arm's length from a music stand.

## Status

This repository currently contains **Milestone 0 plus the first vertical slice**: the architecture,
the local library, Media3 playback, and the synchronized chord table.

There is **no automatic analysis yet**, by design — the roadmap puts playback, projects, correction
and evaluation before any model, so that analysis output has somewhere trustworthy to land. A
project you import starts with an honest empty chart, and you can lay out bars by hand and type
changes into them.

| Area | State |
| --- | --- |
| Chord model, parser, symbol / Roman / Nashville rendering, transposition | Working, unit tested |
| Beat, measure, tempo and section maps with position lookup | Working, unit tested |
| Room persistence, revisions, non-destructive corrections | Working, tested with Robolectric |
| Storage Access Framework import, reference or managed copy | Working, not yet exercised on a device |
| Media3 playback, media session, background playback | Working, not yet exercised on a device |
| Synchronized chord table, current-row tracking, loop / speed / transpose | Working, unit tested |
| Metronome and count-in | Interface only — latency approach not yet validated |
| Stem separation, beat tracking, chord recognition | Not started |
| Ear training | Not started |

See `docs/roadmap.md` for what each milestone delivers and `docs/milestones/` for the checklists.

## Building

Requires JDK 17 or newer and an Android SDK with platform 37 and build-tools 37 installed.

```bash
./gradlew assembleDebug     # build the app
./gradlew test              # JVM and Robolectric unit tests
./gradlew detekt            # static analysis
./gradlew connectedDebugAndroidTest   # instrumentation tests, needs a device or emulator
```

Point the build at your SDK with a `local.properties` containing `sdk.dir=/path/to/android-sdk`,
or set `ANDROID_HOME`.

## Module layout

```text
:app                    navigation shell, DI graph, theme
:core:model             pure Kotlin domain — chords, timeline, chart rows, abstractions
:core:common            dispatchers, time, cross-cutting utilities
:core:database          Room entities, DAOs, schema
:core:data              repository implementations, use cases, mappers
:core:media             Storage Access Framework import, Media3 playback
:feature:library        the local library and import flow
:feature:performance    the chord table and practice controls
```

Dependencies run one way: UI → use cases → repository interfaces → storage, media and (later) model
runtimes. Feature modules depend on abstractions in `:core:model`, never on Room, ExoPlayer or a
model vendor. Further modules from the architecture (`:core:ml`, `:core:audio`, `:feature:editor`,
`:feature:eartraining`, `:benchmark`) are added when their first real code lands.

## Privacy

Everything is local. Nothing is uploaded, and no analysis leaves the device. Imported audio is
either referenced where you keep it — with a persisted read permission and no writes — or copied
into app-private storage at your explicit choice per import. Deleting a project removes the project
and its derived data; a referenced source file is never touched.

## Documentation

- `docs/architecture.md` — layering, module boundaries, threading, persistence
- `docs/decisions/` — architecture decision records
- `docs/roadmap.md` — milestones and exit criteria
- `docs/testing.md` — test layers and what is and is not automated
- `docs/model-registry.md` — the metadata every model must carry before it is integrated
