# Code and product audit — August 7, 2026

## Scope and evidence

This audit covered every production module, the desktop analyzer, the training utilities, database
schemas and migrations, build configuration, CI, and the existing automated tests. It combined
static review with the checks listed at the end of this report. The Android SDK and uncached JVM
dependencies were unavailable in the audit environment, so Android/Gradle verification is clearly
separated from checks that actually ran. No physical device or copyrighted source recording was
available.

## Executive summary

The architecture is sound for an early native application: music and DSP remain Android-free,
machine results and user corrections are separate revisions, analysis work is application-scoped,
and decoding reduces audio to the analysis format before retaining it. The most important risks are
not basic layering problems; they are signal-level ambiguity, Android codec coverage, and missing
product escape hatches for uncertain analysis.

This audit fixed four defects:

1. Tempo octave selection ignored harmony even though harmony contains the evidence that separates
   a slow tactus from continuously articulated subdivisions.
2. Media probing and decoding converted coroutine cancellation into ordinary failures.
3. A single large codec output buffer could overrun the configured decoded-duration cap.
4. A canceled or failed library import could leave the screen permanently showing an import in
   progress.

The tempo change is verified only against synthesized fixtures and code-level reasoning. It has not
been run against *All in Love Is Fair*, *Golden Lady*, or any other real recording, and this report
does not claim that the reported device failure is solved until that test is performed.

## Changes made

### 1. Joint onset-and-harmony tactus selection

**Changed:** `AudioAnalyzer.analyzeRhythm`, a new `MetricalHypothesisEvaluator`, and
`TempoEstimator.curve`.

**User failure:** the onset estimator could choose approximately 130 BPM for a song whose musical
tactus is approximately 65 BPM. Continuous arpeggiation puts a real onset at every subdivision, so
the existing alternate-beat energy check sees no missing pulse and confidently retains double time.

**Implementation:** rhythm now expands the serious onset peaks into a bounded lattice containing
their half- and double-time levels. Each tempo gets its own tracked grid, and every 3-, 4-, and 6-beat
meter/downbeat-phase combination becomes an explicit hypothesis. Hypotheses are ranked by normalized
onset support, beat fit, accent contrast, harmonic-change alignment, and non-neighboring bar-level
chroma repetition. The winning meter is carried into structure analysis, while its tempo anchors the
local curve so later windows may follow drift without returning to a rejected octave.

The winning and runner-up tempo levels remain available, and confidence is reduced according to the
joint-score margin when musical evidence participated. When no accent, harmony, or structural
evidence is usable, onset confidence is retained rather than fabricating certainty from silence.

**Verification:** new full-pipeline synthesized tests cover a 65 BPM progression articulated twice
per beat and a genuine 132 BPM progression. Existing tests cover sparse 60–80 BPM ballads, genuine
100/132 BPM material, a metrically ambiguous 168/84 pulse, tempo drift, and known-tempo analyzer
fixtures. Gradle could not execute in this environment because the Android SDK was absent and the
dependency repositories returned HTTP 403 for uncached artifacts. These tests therefore require CI
confirmation.

**Considered and rejected:**

- Moving the preferred-tempo prior again merely changes which octave ties lose.
- Raising the alternate-onset threshold would break genuine fast or strongly accented material.
- Automatically halving every result in the 120–140 BPM band would repeat the regression that
  turned a real 168 BPM signal into approximately 83 BPM.
- Running full chord recognition for every tempo candidate would be expensive and circular. Chroma
  change is sufficient for this decision and is already available before chord labels are chosen.
- Replacing the pipeline with an opaque learned tempo model was rejected because there is no
  benchmark, packaged model, device memory measurement, or fallback satisfying the model registry.

**Real-audio limitation:** before this change the physical recording reportedly returned 129 BPM,
approximately twice a tapped 64.5 BPM. No source audio was available here, so there is no honest
after number for that recording.

### 2. Cancellation remains cancellation in media I/O

**Changed:** `PcmDecoder.decode`, `MediaProbe.probe`, and `MediaProbe.checksum`.

**User failure:** canceling a long decode, metadata read, or checksum operation could be caught as a
generic `Exception` and returned as an unreadable-media failure. Upstream code could then mark a
canceled analysis as failed, show a false error, or continue work the user had stopped.

**Implementation:** each boundary now rethrows `CancellationException` before translating genuine
I/O exceptions into domain failures.

**Verification:** this follows the coroutine cancellation contract and matches the explicit
cancellation handling already present in `LocalAnalysisBackend` and `AnalysisEngine`. A platform
decoder cancellation test is still missing because `MediaCodec` cannot run in the host JVM suite.

**Considered and rejected:** returning a dedicated decode-domain cancellation failure was rejected.
Cancellation is structured control flow; converting it to a `Result` value prevents parent scopes
from stopping their children correctly.

### 3. Enforce the decode cap while consuming a buffer

**Changed:** `DecodeSink.append` and `DecodeSinkTest`.

**User failure:** the outer decoder checked `isFull` only after appending an entire codec buffer. A
large output buffer could therefore retain audio beyond the fifteen-minute cap. Besides making the
documented limit inaccurate, this creates avoidable memory pressure on the devices the cap protects.

**Implementation:** sample consumption now stops as soon as the mono output reaches the configured
limit, even when unread samples remain in the current codec buffer.

**Verification:** a new sink test supplies ten samples with a three-sample limit and requires exactly
three output samples. It requires Gradle/CI confirmation in the current environment.

**Considered and rejected:** trimming only in `toDecodedAudio` would still allocate and retain the
excess samples, defeating the memory-safety purpose of the cap.

### 4. Always clear the library import indicator

**Changed:** `LibraryViewModel.onImport`.

**User failure:** if import threw or its coroutine was canceled, the assignment clearing
`isImporting` was skipped. If the ViewModel remained alive, the library could stay stuck in an
importing state indefinitely.

**Implementation:** cleanup now occurs in `finally`; successful and domain-failure messages keep the
existing behavior.

**Verification:** structured-control-flow reasoning. A focused ViewModel regression test is
recommended once the Gradle suite is executable.

**Considered and rejected:** catching every exception and displaying its message would risk exposing
implementation details and would incorrectly consume cancellation. Cleanup without changing the
existing domain-error policy is the safe fix.

## Bugs and risks found but not fixed

### High priority

1. **The tempo correction is not verified on real recordings.** Synthetic signals establish a
   regression boundary, not production accuracy. The three reported tracks must be rerun on the
   physical tablet, with the build SHA and candidate/debug summary recorded.
2. **`PcmDecoder` still lacks an on-device integration corpus.** `DecodeSink` has host tests, but the
   actual extractor/codec loop is device-only. Maintain a small licensed corpus covering AAC-LC,
   HE-AAC/SBR, MP3, FLAC, mono/stereo, unusual channel counts, output-format changes, truncated files,
   and duration caps, then run it as instrumentation tests on representative hardware.
3. **Tempo confidence is not statistically calibrated.** The new margin prevents a close harmonic
   choice from retaining certainty, but no dataset establishes that a displayed value such as 0.8
   corresponds to an 80% correctness rate. Confidence should be reliability-calibrated on annotated
   real audio and the UI should describe uncertainty in user language.
4. **A residual approximately 45 ms beat offset remains unexplained.** Do not hide it with another
   global correction until decoder timestamps, spectral-window center, onset smoothing, peak
   localization, and playback output latency have been measured independently.

### Medium priority

1. **Concurrent calls to `AnalysisEngine.start` are not serialized.** Two callers can both observe no
   running map entry and create duplicate jobs before either stores its coroutine. The UI currently
   provides one ordinary entry path, so this was not changed without a reproducible trigger. A
   per-project mutex or atomic ownership record should protect creation.
2. **Source deduplication is probabilistic for files over 32 MiB.** The checksum hashes only the
   leading bytes plus size. Two distinct exports with identical headers, size, and leading audio can
   collide. This is a deliberate responsiveness tradeoff, but the field should be understood as a
   fingerprint rather than a cryptographic whole-file checksum.
3. **Revision activation trusts its caller.** `RoomChartRepository.setActiveRevision` does not verify
   that the revision belongs to the supplied project. Current callers are internal, but validating
   ownership in the transaction would prevent accidental cross-project pointers as features expand.
4. **Library search is linear and locale-implicit.** This is fine for a small personal library, but
   repeated `lowercase()` scans and full list collection should move to normalized indexed database
   search if libraries become large.
5. **Playback-controller shutdown can race its asynchronous connection.** If `release` occurs before
   `MediaController.Builder.buildAsync` completes, the completion listener can still install the
   controller. The application singleton is normally process-lived, so no observed user failure
   justified a speculative lifecycle rewrite.
6. **Long analyses are checkpointed in status only, not resumable computation.** Process death
   preserves an honest job history but restarts decoding and DSP. The existing stage records create
   the right foundation; resumable artifacts need explicit versioning and storage budgets.

### Low priority

1. `SongChart.indexOfChordAtOrAfter` uses a linear search after its binary containing lookup. It is
   unlikely to matter for ordinary chart sizes, but the next-start lookup can also be binary.
2. Section lookup is linear. This is harmless with a handful of sections and should not be optimized
   until profiles show a need.
3. Some UI state uses `MutableStateFlow` for one-shot messages. The explicit acknowledgment prevents
   most duplicate display, but a buffered event channel would make delivery semantics clearer.
4. The handoff document contains the typo “centerd”; it should read “centered.” It was left untouched
   because this report focuses on product code and the typo has no runtime effect.

## Feature and product recommendations, ranked

1. **Ship half-time, double-time, and tap-tempo correction.** No estimator can infer every listener's
   intended tactus from audio alone. Treat the action as a non-destructive metrical reinterpretation:
   preserve the machine revision, choose retained-beat parity when halving, interpolate when doubling,
   and recompute meter, downbeats, chord spans, sections, and tempo segments.
2. **Show tempo uncertainty and alternatives.** The analysis already retains candidates. Present
   “about 65 BPM” and a one-tap “129 BPM” alternative when the decision is close, just as chord
   alternatives are preserved rather than hidden.
3. **Create a real-audio evaluation harness.** Record strict tempo accuracy, octave-tolerant accuracy,
   beat F-measure with continuity, downbeat accuracy, chord overlap score, runtime, and peak memory by
   device/profile. Keep copyrighted evaluation audio outside the repository with reproducible local
   manifests.
4. **Add chart import UI.** `ChartImporter` exists and is tested, but users cannot invoke it. Include a
   conflict preview and always create an imported revision rather than replacing machine/user history.
5. **Make analysis resumable after process death.** Cache versioned reduced features—not the huge full
   spectrogram—at deliberate checkpoints, validate available storage, and discard incompatible caches
   after algorithm changes.
6. **Add a waveform cache and precise boundary editor.** The processing pipeline currently reports
   waveform generation as skipped. A downsampled peak cache would make chord-boundary correction and
   beat-offset diagnosis materially easier.
7. **Complete settings with user-facing analysis defaults and accessibility controls.** Preserve the
   current profile/detail semantics and explain their accuracy, memory, and notation tradeoffs.
8. **Package the desktop analyzer in releases.** It is valuable for reproducible debugging and can
   produce importable output, but users and testers currently must build it themselves.
9. **Integrate a trained model only after registry requirements are met.** The training pipeline has
   no on-device inference path. Do not ship one without quality comparisons, memory/runtime benchmarks,
   a fallback, and a visible quality classification.

## Verification record

### Passed

- `python3 -m unittest discover training/tests -v` — 36 tests passed.
- `python3 -m compileall -q training` — all training Python sources compiled.
- `git diff --check` — no whitespace errors.

### Blocked by the environment

- `./gradlew test detekt` — Android SDK location was not available.
- `./gradlew :core:audio:test :core:audio:detekt` — uncached Kotlin/coroutines/serialization artifacts
  could not be downloaded because both configured repositories returned HTTP 403.
- `./gradlew --offline --no-configuration-cache :core:audio:test :core:audio:detekt` — confirmed that
  the required Kotlin, coroutine, serialization, and Detekt artifacts were not present in the cache.
- `./gradlew :app:assembleRelease` — not runnable without the Android SDK and resolved dependencies.

### Required follow-up outside this environment

1. Run the complete Gradle test and Detekt suite in CI.
2. Build and install the release APK on the physical tablet.
3. Reanalyze the three reported *Innervisions* recordings and record selected tempo, confidence, and
   alternatives before making any real-audio success claim.
4. Run the on-device codec corpus and measure the residual beat offset against decoded PCM timestamps.
