# 3. Corrections fork a revision instead of overwriting

**Status:** Accepted · **Date:** 2026-08

## Context

The product's central promise is that analysis is evidence, not fact: the user can disagree with it.
That promise is worthless if disagreeing destroys what the machine said, because then there is
nothing to compare against, nothing to restore, and nothing to learn from later.

## Decision

Machine analysis writes a revision with source `MACHINE`. The first user correction against it forks
a `USER` revision, copies the whole chart onto the fork, and makes the fork active. Subsequent
corrections land on the same fork. `restoreMachineResult()` switches the active revision back to the
machine's without deleting the user's work, and the user revision can be made active again.

Chord and section rows carry a `localId` that survives the copy, so the same musical event can be
traced across revisions.

## Consequences

Storage grows by roughly one chart per fork. A chart is a few thousand small rows, so this is cheap
next to the audio it describes.

`updateChord` therefore has a side effect beyond the chord — it can create a revision and change
which one is active. That is why it returns the revision id it wrote to.

The corrections retained here are what a future training workflow would be built from. Whether they
are ever exported as a dataset is an open product question, not a decision made here.
