# Handoff: audit Hearsay, and solve the tempo octave problem

You are auditing an Android app called Hearsay. It listens to a recording the user owns and
produces an editable chord chart: chords, beats, bar lines, key, sections. Everything runs on the
device; nothing is uploaded.

**Repository:** `https://github.com/alekpeed/chord`
**Branch:** `claude/app-idea-questions-3hay1n`
**Head at handoff:** `cd66064`

Read `CLAUDE.md` at the repository root first. It is short and it is binding.

---

## Part 1 — The problem you are specifically asked to solve

**The reported tempo is wrong on real recordings, at the wrong metrical level, and it has survived
three attempts to fix it.**

### The evidence, exactly as observed on a physical tablet

| Recording | User's tapped tempo | Reported before | Reported after `cd66064` |
| --- | --- | --- | --- |
| Stevie Wonder — *All in Love Is Fair* | ~70 (probably nearer 65) | 135 | **129** |
| Stevie Wonder — *Golden Lady* | ~100 | 135 | not retested |
| Stevie Wonder — *Too High* | ~99 | 99 — correct | correct |

All three are from *Innervisions*, so encoding is not the variable. `129 ≈ 2 × 64.5`. The estimate
is landing an octave above the musical tactus — the level a person taps.

### What has already been ruled out — do not re-investigate these

Each of these was tested and eliminated. Re-deriving them wastes your budget.

1. **Stale display.** The user uninstalled, reinstalled and re-analyzed. Still wrong.
2. **Wrong build installed.** Confirmed by the user; a version display now exists in Settings
   (`BuildConfig.GIT_SHA`) precisely so this can never be ambiguous again.
3. **The estimator being broken in general.** Sweeping known tempos through the full
   `AudioAnalyzer` at all three profiles recovers them: 75→76, 90→89, 100→99, 120→117, 135→136.
4. **Decoder sample-rate error.** `DecodeSink` was discarding the codec's authoritative output
   format in favor of the container's declared one (a real bug, fixed in `dd0ae5f`, HE-AAC being
   the motivating case). It made no difference to the reported tempo.
5. **Soft or sustained material.** A no-transient pad fixture tracks accurately at every tempo.
6. **Swing.** A shuffled groove fixture at 85/90/95 BPM tracks accurately at every swing strength.
7. **A constant fallback.** It is not a hardcoded or degenerate value: white noise gives 152,
   silence 215, and a genuinely 100 BPM signal gives 99.4.

### What is known to be true

- `TempoWeakPulseTest` (in `core/audio/src/test/.../TempoWeakPulseTest.kt`) **reproduces the
  doubling in a fixture**: a 70 BPM ballad with ~35% of beats left unstruck was reported at
  136 BPM with **confidence 1.00** before the fix.
- Confidence has been observed **anti-correlated with correctness**: the wrong 136 answer reported
  1.00, while a rubato variant that returned 61.5 (much nearer the true 70) reported 0.17.
  `CLAUDE.md` states that nothing may present a guess as a fact. This is a violation, and it is
  arguably a worse defect than the wrong number.

### Why the three attempts failed

The relevant code is `core/audio/src/main/kotlin/com/alekpeed/hearsay/core/audio/rhythm/BeatTracker.kt`.

1. **Recentering the counting prior from 120 BPM to 100** (`PreferredBpm`). The center is where
   octave ties break; at 120 it favored the double of a 67 BPM ballad by roughly 17%. This moved
   the answer from 135 to 129 — the decision changed, the level did not.

2. **A half-time check** (`settleHalfTime`). It lays the winner's beat grid over the onset envelope
   and halves the tempo if the median consecutive-pair strength ratio falls below
   `HalfTimeThreshold` (0.40) — i.e. if every other "beat" has nothing under it.
   **Why it cannot work on this song:** *All in Love Is Fair* is continuously arpeggiated. There is
   a real piano onset under every grid point at 129 BPM. Onset energy alone cannot distinguish the
   tactus here; both levels physically exist in the signal.

3. **Anchoring the tempo curve** to the settled global estimate within a `DriftRange` of 1.35, so
   per-window estimates can follow drift but cannot re-decide the metrical level.

### The actual research problem

Tactus selection when onset energy is present at multiple metrical levels. The evidence that
distinguishes them is **not** in the onset envelope — it is in **harmonic rhythm** (how often
chords change), **bar-level self-similarity**, and **beat-strength periodicity at the bar level**.

This codebase already computes things that bear on it and does not use them for this decision:

- `chordChangeStrength` (in `core/audio/.../harmony/`) — where harmony changes
- `DownbeatEstimator.estimateBeatsPerMeasure` — already scores 4/3/6 groupings by contrast
- `SectionDetector` — self-similarity over chroma

A tempo whose bar lines coincide with chord changes is more likely to be the tactus than one whose
bar lines fall mid-chord. That relationship is currently one-directional: rhythm is computed first
and harmony second, so harmony never informs the tempo. **Changing that ordering is permitted and
may well be the answer**, provided the pipeline stays checkpointable — see `AudioAnalyzer.analyze`,
whose stage ordering is deliberate and documented.

### What would count as solving it

- A 60–70 BPM ballad with sparse or arpeggiated accompaniment reports its tactus, not the double.
- The cases that already work keep working. `TempoWeakPulseTest` and `AudioAnalyzerTest` guard
  these; one earlier attempt fixed 70 by breaking a genuine 168 into 83, and that test is what
  caught it. **Do not delete or weaken a test to make a change pass.**
- Confidence drops when the choice is genuinely close. An honest "I am not sure" is a better
  outcome than a confident wrong number, and is what `CLAUDE.md` requires.
- Above ~150 BPM with no accent pattern, either octave is acceptable — standard tempo evaluation
  treats both as correct, and `TempoWeakPulseTest` encodes this.

### An orthogonal thing you may also do

A **half/double toggle** on the chart, plus tap-along tempo. The beat grid's *shape* is generally
right and only its labeling is wrong, so halving is exact rather than a re-estimate. Every
commercial chord app ships this because no algorithm gets tactus right on every recording. This is
a product fix and does not replace the analysis fix, but it is the thing that makes the app usable
regardless of what the estimator concludes. `PerformanceViewModel` and `ChordTable` are where it
would live.

---

## Part 2 — Audit the rest of the codebase

Beyond the tempo problem, audit for correctness bugs, real inefficiencies, and missing features.
Some context on where the weak ground is:

- **Nothing was tested on a physical device until very recently.** Every bug found on hardware in
  one evening — a service race that stranded analyses, memory exhaustion, a chart erased at the
  moment it was saved, a 140 ms systematic beat offset — was invisible to a full green test suite.
  Treat "the tests pass" as weak evidence.
- **`MediaCodec` cannot be executed off-device**, so `PcmDecoder` has never actually run in any test
  or CI. `DecodeSinkTest` exercises `DecodeSink` in isolation under Robolectric. That gap has hidden
  multiple real bugs. Scrutinize it accordingly.
- **`core/model` and `core/audio` are plain Kotlin JVM modules** with no Android dependency. This is
  a deliberate constraint: all music theory and DSP is testable without an emulator, and the desktop
  tool in `tools/analyzer` reuses the same code. Preserve it.
- **Known-incomplete, not defects:** no "import a chart" UI (`ChartImporter` exists and is tested but
  nothing calls it); no ONNX inference, so the training pipeline in `training/` produces a model with
  nowhere to run; `Settings` is a placeholder; the desktop analyzer is not packaged into releases.
- **A residual ~45 ms beat offset** was measured and is unexplained. Windows are now centerd, which
  removed 140 ms of a systematic ~186 ms lead. The remainder is real and its cause is not known.

## Part 3 — Constraints

These are not negotiable.

- **American English everywhere** — code, comments, commit messages, and every user-visible string.
  `CLAUDE.md` explains the two identifiers deliberately spelled `CANCELLED`; leave them alone. They
  are persisted to the database as literal strings and renaming them orphans stored rows.
- **Chords are stored structurally, never as display strings.** `ChordFormatter` renders; nothing
  parses a rendered symbol back.
- **Nothing may present a guess as a fact.** Every recognized chord carries a confidence, keeps its
  runners-up, and can be corrected without destroying what the analysis originally said.
- **No destructive database migrations.** `HearsayDatabase` is at version 4 with hand-written
  migrations and no `fallbackToDestructiveMigration`.
- **An analysis job's lifetime belongs to `AnalysisEngine` and the application scope**, never to a
  service, screen or ViewModel. A job whose creation can be cancelled by a caller going away is a
  job that strands the UI — that exact defect has already been fixed once.

## Part 4 — Building and verifying

```bash
./gradlew test detekt          # must be green before you report
./gradlew :app:assembleRelease
python3 -m unittest discover training/tests
```

`detekt` includes ktlint formatting and is enforced. CI (`.github/workflows/apk.yml`) builds a
signed APK on every push and replaces a rolling GitHub release.

## Part 5 — What to report back

You are permitted to fix bugs and errors and to improve efficiency. You are asked to report
**every single change in detail**. For each one:

1. **What you changed** — file and function.
2. **Why** — the defect, stated as what would go wrong for a user, not as a code smell.
3. **How you verified it** — the test, the measurement, or the reasoning. If you could not verify
   it, say so explicitly. That distinction matters more than anything else in your report.
4. **What you considered and rejected**, where a reasonable person would have expected a different
   choice.

Separately list, without fixing:

- Bugs you found but chose not to fix, and why.
- Features or improvements you would recommend, ranked.
- Anything you believe is wrong that you could not prove.

**On the tempo problem specifically:** if you solve it, show the before-and-after numbers on
fixtures and say plainly which real recordings you could not verify against. If you do not solve
it, say what you ruled out and what you would try next. A precise negative result is worth more
than another plausible-sounding change that moves 135 to 129.

Do not claim a fix works on real audio unless you have run it on real audio. That failure mode has
already cost this project several rounds.
