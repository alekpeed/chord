# Plan: recognition accuracy — as complex as the evidence allows, never more

**Repository:** `https://github.com/alekpeed/chord` · **Branch:** `main` · **Written at:** `3e92703`

The goal, in the owner's words: as complex a chord recognition system as possible **while still
being accurate**. That ordering is the design principle, not a hope. Complexity becomes a per-chord
*earned* property: a name like `C13b9` is a stack of claims — root C, dominant seventh, flat nine,
thirteenth — and each claim must independently clear its own evidence bar. The displayed name is
the deepest rung of the ladder where every rung below holds. Ambiguity degrades the name toward
something simpler that is still true; it never invents. A blank row is the honest floor.

Every phase below was fact-checked against the code and adversarially critiqued before writing.
Claims are labeled **measured** (observed on real audio this week), **verified** (read in the code
with file:line), or **hypothesis** (plausible, must be measured before believed). The critique
caught the draft presenting two hypotheses as measurements; they are relabeled here, which is what
`CLAUDE.md` demands of the product and should demand of its plans.

This plan supersedes the forward-looking half of `handoff-recognizer-2026-08-10.md`. One
correction to it: open bug 2 (the APK's doubled tempo) has since been **fixed** in `3e92703` —
`DecodeSink` now distinguishes the container's guess from the codec's own format, with Robolectric
tests for both 2× shapes. On-device confirmation is still owed (Phase 0a). The `match_audio`
false-positive matcher was fixed in the same commit.

---

## Why this order

Three lessons this week, paid for in reverted commits:

1. **Constants cannot fix structural problems.** The overtone scalar was swept end to end; no value
   works, because a phantom fifth and a played fifth occupy the same folded bin. The fix must move
   to a domain where they differ (the spectrum), or to a learned front end.
2. **Sequential filters fight each other.** Half the week's bugs were pairwise interactions between
   passes (confirmation vs. sandwich, elimination masking the overtone bug). The fix is one joint
   objective, not another pass.
3. **Hard gates verified on synthetic fixtures are weak evidence.** A hard beat-1/3 rule would have
   deleted 51% of a real chart and kept the wrong half. Gates should become costs; fixtures contain
   no melody or voice, so the entire bug class under attack is invisible to them by construction.

So: ground truth first (or "better" stays vibes), then make the front end stop lying, then one
decoder that weighs everything at once, then learning to raise the ceiling.

---

## Phase 0 — Ground truth and harness

*Nothing here needs new theory. Everything later is unmeasurable without it.*

**0a. Confirm the decoder fix on the device.** Install the current APK; This Love must read 56 BPM
and its chart must run to 4:29. This gates everything: corrections collected from a 2×-decoding
app would poison the ground truth at the source, and every beat-denominated threshold is wrong by
2× until confirmed. Free — the fix is already shipped; this is the ear-check.

**0b. In-app corrections exporter.** The schema already supports it (verified):
`ChordEventEntity.revisionId → RevisionEntity.projectId → MediaAssetEntity.uri/checksum`, with
`source` distinguishing MACHINE from USER revisions and `userConfirmed` per row. Export
Isophonics-style `.lab` (`start end label`, Harte syntax — `read_lab` parses exactly this,
verified) from the app, since the Room database lives on the device and the desktop cannot reach
it — that is a product decision, made here: an in-app "export corrections" action producing
shareable `.lab` files.

Two traps the critique caught, both now requirements:

- **Provenance mask.** A forked USER revision's unedited rows are the machine's own output. Export
  them all and the benchmark rewards the recognizer for agreeing with itself. Only user-edited or
  `userConfirmed` spans count as truth; report confirmed and unconfirmed accuracy separately.
- **Serializer honesty.** The app's display symbols are not Harte, and `read_lab` silently maps
  anything unparseable to no-chord (verified) — a mismatch produces plausible scores, not errors.
  The Kotlin `Chord → Harte` serializer needs round-trip tests before any number is believed,
  per `CLAUDE.md`'s round-trip rule.

**0c. Evaluator in Python, scoring structurally.** Implement `evaluate` inside `training/`
(reusing `harte.py`), consuming the analyzer CLI's existing `.hearsay.json` plus a `.lab`. Compare
**components** — root, bass, shell quality, seventh, each color tone — never rendered symbols
("nothing parses a rendered symbol back"). Metrics: root accuracy, maj/min accuracy,
exact-structural accuracy, boundary F-measure, per-component accuracies (Phase 3 needs these
anyway), and the beat-position histogram — the best health metric found this week: peaked on 1 and
3 when the analysis is right, flat when changes are noise, peaked on 2/4 when the downbeat is off.

**0d. Checked-in feature fixtures.** Serialize a real track's computed features — chromagram,
onset envelope, boundaries, bass observations — as test fixtures. Decoder and rhythm changes then
regression-test in CI against real-audio features without shipping audio and without a device.
This closes the gap the synthetic fixtures cannot cover.

**0e. The benchmark itself.** Correct charts for at least ten songs across tempo and density —
the one step only the owner can do, and the highest-leverage hour in this plan. One track is
single-song overfitting; the week's own trap in real-audio clothing.

---

## Phase 1 — Front-end truthfulness

*Imagined chords are born here. Every subsection gates on the Phase 0 harness: no improvement on
the benchmark, no merge.*

**1a. Tuning estimation and correction** before folding to pitch classes. Recordings are not
reliably A440; a global offset smears every bin boundary. Standard, cheap, and it also fixes a
verified independent bug: the bass tracker truncates MIDI pitch with `.toInt()`, a systematic
up-to-a-semitone flat bias — rounding plus the tuning offset, in one change.

**1b. Bass evidence rebuilt on the tracker that already exists.** Verified: `BassTracker` is a
real time-domain autocorrelation pitch tracker (38–400 Hz, clarity-gated) — and its output is used
by *nothing* except a progress-stage flag, while chord identity uses a folded low-band chromagram
built with the **same band weighting as the main chroma**. The measured failure this addresses —
a C3's G-partial out-voting the C fundamental in the bass chroma — predates the band-center change
in `df7d8d4`, so **re-measure first** (hypothesis until then; the arithmetic says it persists:
at the 293.66 Hz center a G4 partial still weighs 0.96 against 0.72 for a C3 fundamental). The
work: run the tracker before HARMONY instead of FINALIZING, emit notes per recognition span rather
than per beat (or the plan creates a *new* time-base mismatch — critique's catch), and A/B it as
added evidence alongside `persistentBassRoots` on the harness before deleting the chroma path.

**1c. Bass-band frequency resolution.** Hypothesis, no longer stated as measurement: low-bin smear
(5.4 Hz bins at 4096/22050 vs 7.7 Hz per semitone at C3) contributes to phantom neighbors of
strong roots. **Measure the cheap answer first:** MaximumQuality already runs an 8192 FFT
(verified) — ~2.7 Hz bins, which may already resolve bass semitones, making desktop need nothing
new. If insufficient, add a long-FFT pass restricted to bins below ~350 Hz, **streamed via
`Spectrogram.forEachFrame` and never materialized** — the memory lesson is written into the code's
own comments, and the bass chroma's second STFT is the exact precedent.

**1d. Deconvolution instead of the overtone scalar — the centerpiece.** Measured and settled: no
scalar works; the sweep is in the handoff. The fix operates before folding, where a real G4 differs
from a C3's phantom G4 — the real note brings its own partial stack (D5, B5...), the phantom
brings nothing. Fit per-semitone note templates (fundamental + decaying harmonic stack) to each
spectral frame — NNLS or matching pursuit — and fold the fitted *activations* into chroma. This is
the standard remedy in the literature for exactly this failure, and it is the single change that
most directly serves "complex but accurate": every claimed chord tone must be a fitted note with
its own harmonic evidence, not folded residue.

Honesty requirements: the scalar path stays as the tablet profile until the deconvolution is
benchmarked on device against the model registry's principles, and if desktop and tablet run
different front ends, the quality tier must be **visible to the user** — the registry's
classification rule extended to DSP profiles, or the accuracy gap between devices becomes
invisible and undebuggable.

---

## Phase 2a — One time base, then the downbeat

*Split from the draft's Phase 2 on the critique's argument: this half is cheap, fixes an open bug,
and must precede the decoder; the two-pass half must follow it.*

Verified precisely this week: chords are recognized over `boundaries` — the beat grid **merged
with harmonic-novelty peaks** — while beats, bars and downbeats use the pure beat grid. Two time
bases. Three attempts to feed decided chords into downbeat scoring failed, one scoring 0/9 — the
signature of an inverted or offset mapping, not a mis-weighting. Additionally verified, and
exactly where an off-by-one lives: **two independent phase estimators exist** —
`MetricalHypothesisEvaluator` computes a `downbeatPhase` that is *never read*, while
`DownbeatEstimator.spans` re-derives phase on its own.

Order of work, as the handoff prescribed and the draft plan wrongly skipped:

1. **Trace first.** Dump the four phase scores per span and each chord start's beat index under
   both time bases. Understand the inversion before touching any weight.
2. **Unify.** One time base for chord spans and metrical scoring; reconcile the two phase
   estimators into one.
3. **Then** feed decided-chord changes and bass-note onsets into the (single) phase decision.

Acceptance is the histogram flipping to peak on beat 1 — measurable in-session from fixtures and
on the benchmark once 0e exists.

**The half/double-time control** ships here, budgeted honestly: it is not a checkbox (verified: no
such UI exists in either module). Halving tempo rewrites beats, bars, downbeats and chord spans,
and per the revision model it must land as a USER-revision correction that leaves the machine
analysis intact. It is also the permanent product escape hatch: no algorithm gets tactus right on
every recording, and this is the feature that makes the app usable when the estimator loses.

---

## Phase 3 — Factorized evidence, one joint decode

*The architectural core, and the change most likely to work precisely because it is not a constant.*

**Factorize.** Replace the flat template list as the unit of decision with separate evidence
accumulations: P(root), P(bass | root), P(shell quality), P(seventh), P(each color tone) — sourced
from the deconvolved chroma and the bass tracker. Evidence for "the root is A-flat" then
accumulates across `Ab`, `Abmaj7`, `Abmaj9` instead of splitting between rivals; the
Gmaj9-versus-Bm7 class of error becomes a bass/root question, which is the question the bass
tracker answers.

**Decode once.** A semi-Markov, duration-explicit decode over segments with one objective:
acoustic fit + bass agreement + root-change cost + beat-position cost (cheap on 1 and 3, dear
off-beat — the sound form of the owner's instinct) + duration prior in beats + complexity-ladder
cost + a mild key prior. This *replaces* the sequential stack (eliminate → Viterbi → runs →
confirm → bass gate → sandwich → enricher) whose pairwise interactions caused half the week's
bugs.

Two corrections from the critique, adopted:

- **Gates become strong costs, not infinities.** The draft kept `ChordCandidateGate` as hard
  constraints inside the decode while citing the lesson that hard gates fail; that was
  inconsistent. Musical validations enter the objective as large finite costs. The one exception
  may be "a chord must contain the held bass note," which earned hard status on real audio — and
  even it should be re-scored as a cost first and only hardened if the benchmark demands it.
- **The schema work is named, not assumed.** Per-component posteriors do not fit today's one-float
  columns (verified). They ride in an extended `chordJson` payload or new columns via a
  hand-written, non-destructive migration (database v4 → v5) planned alongside the decoder, per
  `CLAUDE.md`'s migration rule.

**Complexity tiering, the product face of the whole plan:** report the most complex name whose
every component independently clears its evidence and persistence bars; degrade rung by rung
otherwise; keep every component's posterior and the alternates in the chart. `ChartDetail`
(Basic/Standard/Advanced) already exists as the display knob (verified).

**Shippable intermediate state:** the old stack remains behind a flag until the joint decoder
beats it on the Phase 0 benchmark. No flag day.

---

## Phase 2b — Two-pass grid re-estimation *(deliberately after Phase 3)*

Rhythm needs harmony and harmony needs rhythm. The circularity becomes explicit: pass 1 produces a
provisional grid and provisional chords; pass 2 re-estimates grid and downbeats *with* the decoded
harmony and bass notes, then runs the final decode against the final grid.

It sits after Phase 3 because, as ordered in the draft, pass 2 would have consumed the *old*
stack's chords — re-estimating the grid from the output of the known-bad recognizer. It also
knowingly abolishes the pipeline's documented "no stage looks ahead" property (which lives in
`AudioAnalyzer`'s own documentation, not in `CLAUDE.md` — the draft cited a rule that does not
exist where it claimed). That property is load-bearing for the persisted stage model: verified,
`LocalAnalysisBackend` maps a *linear* run onto stage records, so HARMONY running twice needs the
stage model extended, not worked around. And it roughly doubles runtime on top of the
deconvolution — so Phase 0 must first record baseline x-realtime figures per profile (none exist
anywhere in the repo; the CLI already prints the number, it has just never been written down).

---

## Phase 4 — The learning loop

The DSP path above has a measured ceiling (the repo's own README: roughly 60–70% maj/min for
systems of this kind, against 80–85% for trained ones). Everything in Phases 0–3 raises the floor
and — critically — produces the training data and the decoder a model plugs into.

- **Inference before training matters:** ONNX Runtime Mobile in the app, behind the model
  registry's gate (benchmarked memory/runtime on the real tablet, defined fallback = the DSP path,
  user-visible quality tier). Verified: no ONNX reference exists anywhere in the Kotlin side today.
- **The model replaces only the emission stage** — factorized heads for root/bass/quality/
  extensions — and shares the Phase 3 decoder with the DSP path, so the fallback is honest and
  both paths improve together.
- **Feature parity is a real problem, stated plainly:** `training/` is CQT-based; the app's front
  end is STFT-derived. Either an on-device CQT gets built and benchmarked, or the model is
  retrained on the app's own front-end features. Decide by measuring both; do not hand-wave it.
- **Data:** the corrections exporter feeds personal fine-tuning — the dataset that matches the
  owner's actual music and taste, which no public corpus does. RWC Popular is the least-encumbered
  published corpus but is distributed through an application process with a media fee, and
  individual (non-institutional) access is **unverified** — confirm before depending on it.
  Self-recorded isolated chords supplement the rare qualities only (they teach timbre, not the
  temporal statistics the decoder needs).
- **Optional, measured before believed:** combining mix + separated-stem predictions. Plain stem
  substitution is a measured dead end; the combination variant is untested, and stays optional
  until the harness says otherwise.

---

## Sacrifices accepted

- **Speed.** Deconvolution, a possible long-FFT bass pass, joint decoding, and eventually two
  passes. Desktop defaults to Maximum and eats the cost; the tablet keeps a lighter profile with
  its quality tier shown. Budgets come from Phase 0's recorded baselines, not vibes.
- **This week's gate code.** Much of it becomes costs inside the decoder or is deleted. It was
  scaffolding; the measurements it produced are the durable part.
- **The linear-pipeline property**, consciously, in Phase 2b — with the stage-persistence redesign
  that entails, not behind its back.
- **A database migration** for per-component posteriors — hand-written, non-destructive, v4 → v5.

## What each phase needs to be checked

| Phase | Verifiable by harness/CI in-session | Needs the device or the owner's ear |
| --- | --- | --- |
| 0 | evaluator, exporter round-trips, fixtures | 0a APK confirmation; 0e corrections |
| 1 | benchmark deltas on exported charts + fixtures | tablet runtime/memory for 1d |
| 2a | histogram flip on fixtures + benchmark | toggle UX |
| 3 | benchmark deltas; old-vs-new behind flag | — |
| 2b | benchmark + recorded runtime budget | tablet runtime |
| 4 | training metrics on exported data | on-device model benchmarks (registry) |

The single most valuable thing the owner can do at any point: **correct more charts.** Every hour
of correction is ground truth no engineering can substitute, it makes every phase measurable, and
in Phase 4 it becomes the training signal that tunes the system to the exact music it will be
judged on.
