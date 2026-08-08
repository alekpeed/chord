# Model registry

**No models are integrated yet.** This document is the contract a model must satisfy before it can
be, written now so that the first integration is held to it rather than the other way round.

## Required metadata

Every model entry must carry:

| Field | Why |
| --- | --- |
| `id` | Stable internal identifier, independent of filename or vendor |
| `version` | Changing it invalidates outputs that depend on it |
| `source`, `license` | A model whose license is unknown does not ship |
| `inputFormat`, `outputFormat`, `sampleRate` | What it accepts, so mismatches fail at the boundary |
| `supportedStems` / `supportedLabels` | What it actually produces, not what it is marketed as |
| `approximateMemoryBytes`, `deviceTier` | Whether a given tablet can run it at all |
| `checksum` | Downloads are verified before use |
| `runtime` | ONNX Runtime Mobile initially; the field exists so it is not the only option |
| `quality` | `PRODUCTION`, `PREVIEW` or `EXPERIMENTAL`, surfaced in the UI |

## Integration checklist

Before a model is merged:

1. Registry metadata added.
2. License documented.
3. Adapter written behind the relevant interface — `StemSeparator`, `BeatTracker`,
   `ChordRecognizer`, `NoteTranscriber`, `SectionAnalyzer`. No feature module may reference the
   model or its runtime directly.
4. Fixture test with known input dimensions and validated output mapping to canonical project data.
5. Memory and runtime benchmarked on a target device, recorded, not estimated.
6. Fallback defined for when it is unavailable, out of memory, or its execution provider is
   unsupported.
7. Quality classified. `PREVIEW` and `EXPERIMENTAL` outputs are labeled as such wherever a user sees
   them.
8. Cancellation verified: a canceled inference leaves the project recoverable.

## Evaluation

A model change cannot be accepted on subjective impressions. Benchmark sets are maintained per genre
— pop and rock, jazz trio, vocal jazz, fusion, big band, acoustic plus electric piano, guitar-led
ensembles, live recordings — and the tracked metrics are weighted chord-symbol recall, root accuracy,
quality accuracy, extension accuracy, bass and inversion accuracy, boundary accuracy, beat and
downbeat accuracy, note onset and pitch accuracy, stem quality, and short-chord retention.

Short-chord retention is listed last but is one of the reasons this product exists: the common
failure of existing chord apps is smoothing a quick passing chord out of the result entirely.

## Distribution

Large models are not bundled in the base APK. Model packs are downloaded on request, resumable,
checksum-verified, with a storage estimate shown before download and removal available afterwards.

## Device tiers

Capability is classified from measured behavior rather than model name: `BASIC` (playback and
precomputed projects only), `STANDARD` (fast local analysis), `HIGH` (balanced), `ADVANCED` (maximum
quality). Users can override the recommendation.
