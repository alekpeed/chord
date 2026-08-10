# Handoff: chord recognition, after the August 10 session

**Repository:** `https://github.com/alekpeed/chord`
**Branch:** `main`
**Head at handoff:** `6d1b6ab`
**State:** clean. `./gradlew test detekt`, `:app:assembleDebug`,
`:tools:desktop:createDistributable` and `python3 -m unittest discover training/tests` all pass.

Read `CLAUDE.md` first. It is short and binding.

---

## What this session was about

The recognizer emits too many chords, and many of them are not in the recording. Reported from a
real build against a real track, with screenshots: four chords inside one bar of a 56 BPM song; a
G minor in a song containing no B-flat; an 11.6-second chord over audible movement; a passage
heard as Bm7 labeled Gmaj9; names like `Cadd11` and `Dsus2` that nobody voiced.

**The test recording is Maroon 5, "This Love"** (`03 - This Love.mp3`, 4:29, in the user's `~/mp3`).
Almost every measurement below is against it. It reports **56 BPM** and that is very likely
correct — independent autocorrelation makes 57 the strongest period (r=0.462) with 113.5 second
(r=0.284), and the user confirmed the song is nowhere near 95.

---

## What was fixed, and what each was verified against

| Commit | Change | Evidence |
| --- | --- | --- |
| `bb98338` | Candidate elimination gate: root, defining tone, structural shell, seventh persistence | Synthetic fixtures |
| `7f59c9f` | Bass primacy — a chord must contain the held bass note; bass must move for the chord to move | Synthetic fixtures |
| `f6efca2` | Strict presence: no neighbor borrowing, one measurement view not two, defining third 0.22→0.40, no standing down | Synthetic fixtures |
| `df7d8d4` | Chroma band center 440→293.66 Hz, ceiling 2093→1046.5 Hz | Reasoning; the vocal hypothesis behind it was later disproved |
| `1090d25` | Confirmation thresholds expressed in beats, not milliseconds | Real audio |
| `880b98e` | **Beat grid was half a period out of phase** | Real audio: alignment 0.488 → 1.834, 6× |
| `edc0846` | Confirmation 1.3 → 0.9 beats — a one-beat chord had been unconfirmable | Real audio: 11.6 s Gm split into Gm/Bm/Gm |
| `6d1b6ab` | Vocabulary cut: removed `sus2`, `aug`, `mMaj7`, and added 9ths/11ths on plain triads | Real audio: color 50%→37%, simple 51%→67%, count 90→89 |

The two that mattered most are `880b98e` and `edc0846`. The beat tracker was placing beats *between*
the actual onsets — 77% of its beats carried less onset energy than the midpoint between them — and
the cause is still worth understanding: in `layGrid`, the backtrace starts from **one frame chosen
in the last 10% of the recording**, and every beat in the song inherits that frame's phase.

---

## Dead ends — do not re-run these

Each was measured, not guessed. Re-deriving them wastes the budget.

1. **Vocal separation does not help.** Demucs htdemucs two-stem, controlled for tempo (same forced
   535 ms beat for all three), on the real track:

   | audio | chords/min | color | simple |
   | --- | --- | --- | --- |
   | mixed | 31.1 | 59% | 44% |
   | center-cancelled | 38.8 | 62% | 41% |
   | Demucs, vocals removed | 34.4 | 64% | 37% |

   Removing the vocal made churn and color *worse* by both methods. **Caveat worth respecting:** the
   architecture note the user later supplied proposes analyzing mix *and* accompaniment *and* bass
   and **combining** predictions, explicitly warning against trusting the stem alone. That is not
   what was tested, and this result does not refute it.

2. **Center-pan cancellation does not help** (mid below 250 Hz + side above, via ffmpeg). Same table.
   It also removes bass and kick, which the recognizer now depends on.

3. **The overtone constant cannot be fixed by a scalar.** `HarmonicSuppression = 1.15` removes
   **0.69 of every note's energy from its fifth** (the 3rd and 6th partials both land there) and 0.27
   from its major third. A C major triad goes in at 1.00/1.00/1.00 and comes out **C 1.00, E 0.73,
   G 0.31**. That is genuinely wrong. But the sweep:

   | value | fifth loses | outcome |
   | --- | --- | --- |
   | 0.40 (physically right) | 0.24 | plain triads become `C13b9`, `G7b9` |
   | 0.85 | 0.51 | chords stop landing on bar lines |
   | 0.95 | 0.57 | Dm7/G7/Cmaj7 grow phantom b9s |
   | 1.05 | 0.63 | green |
   | 1.15 (current) | 0.69 | green |

   The reason no value works: `voiceChord` puts the root at octave 3 and other tones at octave 4+, so
   a C3's third partial **is** the G4 actually being played — same frequency, same bin. Real
   recordings do this constantly. One scalar has to both remove the phantom fifth and keep the played
   fifth. **The real fix is NNLS deconvolution against harmonic templates, or a learned chroma
   front-end.** Tightening the color enricher to compensate was also tried and was worse: it killed
   legitimate persistent b9s while the phantom ones survived.

4. **Training from scratch is not currently viable.** The user owns none of the annotated corpora
   (Isophonics/JAAH/McGill), `~/mp3` matched almost nothing, and — separately — nothing in the app can
   load a trained model. See "Training" below.

---

## Open bug 1: the downbeat phase is off by one

**Status: diagnosed, three implementation attempts, all reverted. Nothing is committed.**

After the beat-grid fix, chord changes went from evenly scattered (22/30/27/21 across beats 1–4) to
sharply clustered — but on the wrong beats: **19% / 35% / 17% / 29%**. Rotating the downbeat by +3
would put 35% of changes on beat 1 against 19% today.

Note the shape: it is **bimodal**, 35% on beat 2 and 29% on beat 4. The harmonic rhythm is half-bar,
so changes genuinely land in two places and the four-way phase choice is weakly determined by this
evidence alone.

**The diagnosis that still looks right:** `AudioAnalyzer.analyzeStructure` places bar lines using
`chordChangeStrength(chroma, beatTimesMs)` — raw frame-level chroma distance, which moves for a sung
note or a fill as readily as for a chord — while the fully decoded `chords` list sits unused three
lines above it. The decoded chords are the same evidence *after* root validation, defining-tone
validation, temporal confirmation and the bass-movement requirement have all had their say.

**What was tried, and what happened:**

| Approach | Result |
| --- | --- |
| decided chords **added** to chroma evidence, weight 6 | 2 failures — `SilentGapTest`: opening chord starts on beat 4 |
| decided chords **replacing** chroma evidence | 3 failures |
| decided chords, weight 4, only when a span holds ≥3 of them | 3 failures, including a new one: `AudioAnalyzerTest` "Expected most of 9 regions on a downbeat, got 0" |

That last result — **zero**, when random would be ~25% — means the mapping is inverted or offset
somewhere, not merely mis-weighted.

**The strongest lead, untested:** chords are recognized over `boundaries`, which is
`mergeBoundaries(beatTimesMs, changeTimesMs, …)` — the beat grid **merged with harmonic novelty
peaks**. But the attempted `decidedChordChanges` helper snapped chord start times onto the **pure
beat grid**. Those are two different time bases. A chord whose start sits on a novelty peak between
beats gets attributed to whichever beat happens to be nearer, which can systematically shift the
whole histogram.

**Do this before touching any weight:** dump the four phase scores per span, and the beat index each
chord start maps to, for both time bases. `ChordDecisionTrace` already exists for this kind of work.
Understand the inversion first.

## Open bug 2: the APK reports 112 BPM where the desktop reports 56

Same commit, same file. The desktop reports 56 at **all three** profiles (fast, balanced, maximum);
the user reports the APK showing 112. Exactly 2.0× is the signature of a time-base error, not an
estimator disagreement — an uncertain estimator lands on 63 or 47, not precisely double.

`PcmDecoder` is the only component that differs between the platforms, and per the earlier handoff it
**has never run in any test or CI** because `MediaCodec` cannot execute off-device. It has already
produced exactly this class of bug once (`dd0ae5f`, HE-AAC sample rate).

There is still a live hole. `DecodeSink.configure` opens with:

```kotlin
if (resampler != null && output.size > 0) return
```

The sink is configured first from the **container's** declared rate and channel count, and corrected
only on `INFO_OUTPUT_FORMAT_CHANGED`. If a device emits audio *before* announcing its format, the
container's values stick for the whole file. A wrong sample rate scales the resampler ratio by two; a
wrong channel count packs two samples into one frame, halving the output. Either gives exactly 2×.

**Cheapest diagnostic, no code:** scroll to the end of the chart in the APK. The song is 4:29. If the
last chord lands near **2:14**, the decoder handed the analyzer half the audio and the doubled tempo
follows automatically. Also confirm the APK's build SHA in Settings before anything else.

---

## The recommended next change: factorize the label, then decode jointly

This came out of an architecture note the user supplied, and it is the most substantive criticism of
the current design. It needs no ML.

**Factorize.** `ChordTemplates` gives 157 flat, mutually-unrelated classes — `Abmaj9`, `Abmaj7` and
`Ab` share nothing, so evidence for "the root is A-flat" cannot accumulate across them. Scoring root /
bass / quality / extension / no-chord as **separate distributions** addresses several things this
session hit at once:

- the Gmaj9-versus-Bm7 case, where root evidence needs to be separable from quality evidence
- the phantom b9s, where extension probability should be independent of the root decision
- `ChordColorEnricher`'s structural inability to reconsider the root when it finds a color tone — it
  is handed a settled chord and can only decorate it

**Decode jointly.** Every term the architecture note asks for already exists here — audio evidence,
boundary evidence, harmonic continuity, duration likelihood, complexity penalty — but they are applied
as **sequential post-hoc filters**, which is why they fight each other. Two symptoms from this
session: the 1.3-beat confirmation threshold interacting badly with the sandwich collapse, and the
elimination gate masking the overtone bug. A semi-Markov / duration-aware decode that scores them
together is architecturally better and is the change most likely to work, precisely because it is not
a constant to tune.

**Also worth doing, cheap:** make beat position a *cost* rather than a gate — cheap on beat 1, less
cheap on 3, expensive off-beat, fed to the decoder. That is the sound version of the user's own
"only beats 1 and 3" instinct: weak changes on weak beats die, while genuine syncopation and
anticipation survive on evidence. Do not implement it as a hard filter — measured on the real track,
a hard beat-1-and-3 rule would delete 51% of the chart, and because the distribution was uniform at
the time, the half it kept would not have been the correct half.

---

## Measurement harness

Reproduce any number above:

```bash
./gradlew :tools:analyzer:installDist
tools/analyzer/build/install/analyzer/bin/analyzer <song>.mp3 --profile balanced --force --text
```

It writes `<song>.hearsay.json` with `chords`, `beats`, `tempoBpm`, `sections`. The metrics used
throughout: chords per minute, percentage of symbols carrying color (`add|9|11|13|sus|#|b`), and
percentage that are plain triads or sevenths.

**The single best health metric found this session:** the histogram of chord starts by
`beatInMeasure`. A working analysis is strongly peaked on beats 1 and 3. Flat means the chord
changes are noise or the grid is sliding; peaked on 2 and 4 means the downbeat phase is off. It
diagnosed both grid bugs and it is nearly free to compute.

`ChordDecisionTrace` (in `ChordCandidateGate.kt`) reports every candidate's verdict per span with
numbers, and every color decision with its support, persistence and required floors. It is
test/debug only and never reaches the UI. Use it before forming a hypothesis about why a chord won.

---

## Training

`training/` is complete through `export_onnx.py`: Harte parser, CQT features, frame alignment,
pitch-shift augmentation, CNN+BiGRU, training loop, evaluator, ONNX export, 36 passing tests.

**Two blockers, both real:**

1. **Nothing in the app can load the result.** No ONNX runtime anywhere in the Kotlin source. Inference
   must be built before a trained model can be heard, and `docs/model-registry.md` governs: benchmarked
   memory and runtime on real hardware, a defined fallback, a user-visible quality classification.
2. **No corpus matches the user's library.** They own none of the Isophonics/JAAH/McGill recordings.

**`match_audio` in `hearsay_training/dataset.py` has a real bug — fix before trusting any run.** It
guards the *annotation's* key length (`len(key) >= 8`) but never the library filename's, so a short
personal track name is matched by containment against any long annotation title containing it.
Verified: the annotation `01_-_Sgt._Pepper's_Lonely_Hearts_Club_Band` matched the user's unrelated
`07 - Lonely.mp3`, and `Beautiful` matched `04 - Beautiful Eyes.mp3`. It prints a checkmark and
writes an `.npz`. A run reporting "9 tracks prepared" was mostly false positives.

A trustworthy matcher would verify something real: compare the annotation's stated duration (JAAH's
JSON already carries `duration` and it is never used) against the audio file's actual duration,
require artist agreement where available, drop fuzzy containment for exact normalized-title matches,
and send anything uncertain to a list for a human rather than silently accepting it.

**The path that needs no corpus** is the user's own corrections: they are already labeled examples for
music the user owns and cares about. Nothing currently captures them into a training-ready format —
`training/` consumes Isophonics-style `.lab` files, not the app's correction history.

The user also asked about recording isolated chords themselves (Gm7 and its inversions, and so on).
That is well-supported by the literature for the rare classes the free corpora barely contain, and
it reaches 0.97 F-score on its own kind of data — but as a *supplement*, not a replacement: solo
clean recordings teach timbre, not the temporal statistics the recurrent layer needs. Feed them in as
`.lab` files and `read_lab` handles them with no new code.

---

## Working notes

- The user is direct and wants measurements, not speculation. "A precise negative result is worth more
  than another plausible-sounding change." That principle earned its keep three times this session.
- **Do not tune constants until tests pass.** Several failures this session came from exactly that,
  and each time reverting was the right call. If a change needs a magic number to survive the suite,
  the change is probably wrong.
- Do not run commands that were not asked for, and do not re-run a diagnostic whose answer is already
  known from what the user has said.
- `SignalGenerator` fixtures are synthesized chord tones with **no melody and no voice**. The entire
  class of bug being chased is invisible to them by construction. Green tests are weak evidence; the
  real track is the arbiter.
- ffmpeg, Demucs and PyTorch are installed in the session container, and the analyzer CLI runs the
  same `core/audio` code as the app. Real audio can be analyzed directly — do that before theorizing.
