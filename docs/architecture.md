# Architecture

## Shape

```text
UI (Compose)  →  ViewModels  →  use cases  →  repository interfaces  →  Room / Media3 / (later) model runtimes
```

Everything above the interface line is testable without Android. Everything below it is replaceable.

The UI never instantiates ExoPlayer, a Room database, a Worker or a filesystem handle. A feature
module that needs playback asks for `PlaybackController`; a feature module that needs chart data
asks for `ChartRepository`. Both interfaces live in `:core:model`, which has no Android dependency
at all.

## Modules

| Module | Type | Contains |
| --- | --- | --- |
| `:core:model` | Kotlin JVM | Chords, timeline, chart rows, project types, repository and playback interfaces |
| `:core:common` | Android library | Dispatchers, time provider |
| `:core:database` | Android library | Room entities, DAOs, database, schema export |
| `:core:data` | Android library | Repository implementations, mappers, use cases |
| `:core:media` | Android library | Storage Access Framework import, source storage, Media3 playback |
| `:feature:library` | Android library | Library screen, import flow |
| `:feature:performance` | Android library | Chord table, practice controls, correction |
| `:app` | Application | Navigation shell, DI graph, theme, placeholder destinations |

`:core:model` being a plain Kotlin module is a load-bearing constraint, not a convenience: it means
the music theory, the timeline lookup and the chart projection cannot quietly acquire a dependency
on Android, and they run in milliseconds under a normal JVM test.

## The music model

A chord is stored as structure, never as a display string:

```kotlin
Chord(root, quality, seventh, sixth, extensions, alterations, suspensions, additions, omissions, bass)
```

The display layer renders that structure as `G13♭9/D♭`, `G7alt/D♭`, a simplified `G7/D♭`, a Roman
numeral or a Nashville number. Transposition, notation preference and simplification all change what
is drawn and never what is stored.

Triad quality and seventh are modelled separately, so "dominant" and "half-diminished" are derived
properties rather than a second way of spelling a chord that already has a representation. Equivalent
spellings are folded by `Chord.normalized()` — `Cm7♭5` and `Cø7` compare equal, and a correction typed
either way round-trips to the same stored chord.

## The timeline

`SongChart` holds chord regions, the beat grid, sections and the tempo map, each sorted by time, and
answers position queries by binary search. Chord regions are half-open, `[startMs, endMs)`: the chord
landing exactly on a bar line is the one starting there. Getting that backwards makes the table
flicker to the previous chord at every bar.

`ChartRowBuilder` projects a chart into table rows with measure numbers, section boundaries and
rendered symbols already resolved, so the table itself does no music theory while scrolling.

## Playback

One clock. `PlaybackController` wraps a Media3 `MediaController` bound to `PlaybackService`, a
`MediaSessionService`, and publishes position by polling the player. Every visual timeline — the
current table row, and later the waveform cursor and count-in — derives from that position rather
than running a timer of its own, so nothing can drift away from what is actually sounding.

Loop ranges are enforced against the same clock: a position past the loop end seeks back to its start.

## Persistence

Room stores structured data; large artifacts do not go in the database. `MediaAsset` rows record a
URI, a checksum, a size and a lifecycle state; the audio itself is either the user's own file or a
copy in app-private storage.

### Revisions

The rule the schema exists to enforce: **a correction never destroys a machine result.**

Machine analysis writes a revision with source `MACHINE`. The first user correction forks a `USER`
revision, copies the chart onto it and makes it active. The machine's rows stay on disk and stay
queryable, and `restoreMachineResult()` switches back to them without deleting the user's work.

Chord and section rows carry a `localId` that is stable across revisions, so the same musical event
can be traced between what the model said and what the user made of it.

### Migrations

Schemas are exported to `core/database/schemas/` and committed — that is what makes a migration
reviewable and what migration tests run against. Destructive migration is never configured: a
missing migration must fail loudly in development rather than silently wipe somebody's library.

## Concurrency

Coroutines and structured concurrency throughout. Repositories hop to an IO dispatcher so Room never
runs on the caller's thread. A bounded `Decode` dispatcher exists for audio decoding and, later,
inference, capped below the core count — analysis must never be able to starve playback.

ViewModels expose immutable state through `StateFlow`. In the performance ViewModel, table rows are
rebuilt only when the chart or the display settings change; the playback position arrives many times
a second and is used solely to pick which existing row is current.

## Machine learning

Nothing yet, deliberately. When it arrives it goes behind interfaces — `StemSeparator`,
`BeatTracker`, `ChordRecognizer`, `NoteTranscriber`, `SectionAnalyzer` — in a `:core:ml` module, and
no feature module will depend on a model vendor. See `docs/model-registry.md` for what a model must
carry before it can be integrated.

## Not yet built

Called out so nothing here is mistaken for finished: the processing stage DAG and its foreground
execution, stem separation, beat tracking, chord recognition, note transcription, the waveform
editor, ear training, export, and the metronome implementation. The `Metronome` interface exists
without an implementation on purpose — its latency approach has not been measured on real hardware,
and committing to one before measuring would bake in a guess.
