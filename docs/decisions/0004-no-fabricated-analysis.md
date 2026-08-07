# 4. Import does not fabricate a chart

**Status:** Accepted · **Date:** 2026-08

## Context

Milestone 2 needs the performance table to follow a song before any analysis exists, and the
specification calls for "placeholder/manual chord data" to drive it. The obvious shortcut is to
generate a plausible chart on import so every project has something to show.

## Decision

Do not. A newly imported project has `analysisStatus = NOT_STARTED` and no chart, and the performance
screen says so.

Instead, `ManualChart.blankGrid` is exposed as a user action: "lay out bars" asks for a tempo and a
meter, writes a beat grid and one empty chord region per bar, and the user types the changes in. Every
event it writes is attributed to `AnalysisSource.USER` and the revision is labelled as the user's.
`ManualChart.twelveBarBlues` exists for tests and demos and marks its events `AnalysisSource.SEED`.

## Consequences

Manual chart entry is a real feature a musician who knows a tune would use, so the work is not
throwaway scaffolding for Milestone 4.

More importantly, the app never shows a chord it did not either hear or receive from the user. The
same reasoning governs the Ear Training, Processing and Settings destinations: they state what is
missing and what blocks it rather than presenting an empty screen that reads like a finished feature
with no content.
