# 2. Chords are stored as structure, not as display strings

**Status:** Accepted · **Date:** 2026-08

## Context

A chord symbol is a rendering choice. `Cmaj7`, `CM7`, `CΔ7` and `C^7` are the same chord; `G13♭9/D♭`
and `G7alt/D♭` can be the same chord read two ways. The app must transpose, renumber into Roman or
Nashville notation, simplify for performance reading, and later generate ear-training questions from
chord content — none of which can be done reliably against a string.

## Decision

Store `Chord(root, quality, seventh, sixth, extensions, alterations, suspensions, additions,
omissions, bass)` as JSON, alongside denormalized `displaySymbol`, `rootPitchClass` and
`bassPitchClass` columns for search. The structure is the source of truth; the string is a cache.

Triad quality and seventh are modelled separately, so `dominant` and `half-diminished` are derived
properties rather than alternative spellings of chords that already have a representation. This
departs from the specification's example JSON, which carries both `quality: "dominant"` and
`seventh: "minor"`; the two render identically and the orthogonal form has one representation per
chord instead of two.

Equivalent spellings are folded by `Chord.normalized()`: `Cm7♭5` becomes the same value as `Cø7`, and
a slash bass equal to the root is dropped.

## Consequences

Parsing has to be forgiving, because a musician correcting a row should not have to learn this app's
preferred spelling — `Cm7`, `Cmin7` and `C-7` all parse to one chord. It also has to refuse rather
than guess: anything the parser cannot account for returns null and the edit is rejected with an
error, instead of being silently stored as something else.

The project export format in Milestone 8 will need a documented mapping from this structure to the
specification's portable JSON shape.
