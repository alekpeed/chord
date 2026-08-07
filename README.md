# Hearsay

A native Android app that turns a recording you own into a permanent, editable harmonic study
project: synchronized chords, a beat and section map, practice controls, and — later — separated
stems, note transcription and ear training built from your own library.

The name is the design brief. Hearsay is testimony you heard but cannot fully verify. That is what
a chord analysis is, and the app is built to say so: every result carries a confidence, alternates
stay reachable, corrections are yours, and the machine's original answer is never overwritten.

**Tablet-first.** The primary interface is a large table that follows the song bar by bar, meant to
be read at arm's length from a music stand.

## Download

**[hearsay.apk](https://github.com/alekpeed/chord/releases/latest/download/hearsay.apk)**

That link never changes and always serves the newest build. Every push rebuilds the APK and
replaces the release behind it, so it is a moving target by design; the same build is also attached
under a versioned name if you need to tell two downloads apart.

Open it on the tablet and Android will ask permission to install from your browser or file manager —
the app is not on the Play Store. Requires Android 12 or newer.

If the link 404s, the build has not finished yet. `Actions → APK` shows what happened.

## Status

**The app listens to a recording and produces a chord chart.** Import a file you own, press Analyse,
and it works out the beat, the bar lines, the key, the sections and the chords — on device, with a
confidence on every chord and the runners-up kept so you can disagree.

The analysis is signal processing, not a trained model. It reads a clear mix far better than a dense
one, and it says how sure it is rather than presenting a guess as a fact.

| Area | State |
| --- | --- |
| Chord model, parser, symbol / Roman / Nashville rendering, transposition | Working, unit tested |
| Beat, measure, tempo and section maps with position lookup | Working, unit tested |
| Room persistence, revisions, non-destructive corrections | Working, tested with Robolectric |
| Storage Access Framework import, reference or managed copy | Working, not yet exercised on a device |
| Media3 playback, media session, background playback | Working, not yet exercised on a device |
| Synchronized chord table, current-row tracking, loop / speed / transpose | Working, unit tested |
| Tempo, beat and downbeat tracking | Working, verified against synthesized audio |
| Chord recognition with alternates and confidence | Working, verified against synthesized audio |
| Key and section detection | Working, verified against synthesized audio |
| Harmonic/percussive separation | Working — not the ten target stems |
| Processing queue, foreground service, checkpointed stages | Working, not yet exercised on a device |
| Chord correction, alternates, split / merge, revisions | Working, unit tested |
| Ear training — six exercise types from your own songs | Working, unit tested |
| Chart export, text and structured JSON | Working, unit tested |
| Deep-learning stem separation (ten target stems) | Not started |
| Note transcription, waveform editor, metronome | Not started |

**Real-world accuracy is unmeasured.** The recogniser is proven against synthesized audio with known
ground truth; nothing has been run against real recordings with published annotations, and nothing
at all has run on a physical device. See `docs/testing.md`.

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

### Signing

Release builds are signed with a key taken from the environment, or from `local.properties`, and
never from a file in this repository. Without one they fall back to the debug key so
`assembleRelease` still produces something installable.

That fallback has one consequence worth knowing: the debug key is generated per machine, and CI
generates a fresh one on every run. **Until a real key is configured, installing a new APK means
uninstalling the old one first** — Android refuses to replace an app with a build signed by a
different key, and it reports this as a vague "app not installed".

To fix that permanently, make a key once and hand it to CI:

```bash
keytool -genkeypair -v -keystore hearsay.jks -alias hearsay \
        -keyalg RSA -keysize 2048 -validity 10000

base64 -w0 hearsay.jks     # paste this into the secret below
```

Then add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `HEARSAY_KEYSTORE_BASE64` | the base64 text printed above |
| `HEARSAY_KEYSTORE_PASSWORD` | the store password you chose |
| `HEARSAY_KEY_ALIAS` | `hearsay` |
| `HEARSAY_KEY_PASSWORD` | the key password (same as the store password unless you changed it) |

Keep `hearsay.jks` somewhere safe and out of the repository — `.gitignore` blocks `*.jks`, because
anyone holding that file can publish an update Android will install over yours. Losing it means
every future build needs an uninstall-and-reinstall.

For local release builds, put `HEARSAY_KEYSTORE=/path/to/hearsay.jks` and the three passwords in
`local.properties` instead.

## Module layout

```text
:app                    navigation shell, DI graph, theme
:core:model             pure Kotlin domain — chords, timeline, exercises, export, abstractions
:core:audio             pure Kotlin DSP — FFT, chroma, beat tracking, chord recognition
:core:common            dispatchers, time, cross-cutting utilities
:core:database          Room entities, DAOs, schema, migrations
:core:data              repository implementations, analysis backend, use cases
:core:media             Storage Access Framework import, PCM decoding, Media3 playback
:feature:library        the local library and import flow
:feature:performance    the chord table, practice controls and correction
:feature:processing     the analysis queue and its foreground service
:feature:eartraining    exercises generated from analysed songs
```

`:core:model` and `:core:audio` are plain Kotlin modules with no Android dependency. That is a
constraint, not a convenience: it means the music theory and every analysis algorithm run under an
ordinary JVM test in milliseconds, with no emulator in the loop.

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
