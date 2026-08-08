# Working in this repository

## Language

**American English everywhere.** Code, comments, commit messages, documentation, and every string a
user can see. Analyze, recognize, normalize, behavior, color, center, license, artifact, modeling,
labeled, canceled, practice.

This is not a style preference to weigh against others. British spellings have been corrected in
this repository once already; reintroducing them is a regression.

Two identifiers are deliberately spelled `CANCELLED` and are not to be "fixed":

- `JobStatus.CANCELLED` and `AnalysisFailure.Cancelled` — the enum name is written into the
  `analysis_jobs` table as a literal string. Renaming them silently orphans every stored row.

## Music theory

Chords are stored structurally, never as display strings. `ChordFormatter` renders; nothing parses a
rendered symbol back. Adding a quality means extending `Chord`, the parser and the formatter
together, with a test for the round trip.

## Analysis

Nothing may present a guess as a fact. Every recognized chord carries a confidence, keeps its
runners-up, and can be corrected without destroying what the analysis originally said.

An analysis job's lifetime belongs to `AnalysisEngine` and its application scope — never to a
service, a screen or a ViewModel. A job whose creation can be cancelled by a caller going away is a
job that strands the UI, which is exactly the defect fixed in `AnalysisService`.

## Models

`docs/model-registry.md` governs. No model ships without benchmarked memory and runtime on real
hardware, a defined fallback, and a quality classification the user can see.
