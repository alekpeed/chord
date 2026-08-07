# Testing

## Layers

| Layer | Where | Runs in CI | Covers |
| --- | --- | --- | --- |
| JVM unit tests | `:core:model` | Yes | Chord parsing and rendering, Roman and Nashville conversion, transposition, timeline lookup, chart row projection |
| Robolectric tests | `:core:database`, `:core:data` | Yes | Room round trips, cascade deletion, revision forking, non-destructive corrections |
| ViewModel tests | `:feature:*` | Yes | State derivation, current-row tracking, correction handling, process-death restore |
| Compose UI tests | `:feature:performance` `androidTest` | **No** | Row rendering, selection, accessibility semantics |

Run everything CI runs with `./gradlew test detekt`.

## What is not automated, and why

**Instrumentation and Compose UI tests do not run in CI.** They need a device or an emulator, and
the runner has no KVM, so an emulator would be unusably slow. They are written and run locally with
`./gradlew connectedDebugAndroidTest`. This is a gap, not a decision that they do not matter.

**Nothing device-dependent has been verified on hardware yet.** Specifically: real Storage Access
Framework permission grants and their revocation, audio decoding of each supported container,
background playback with the screen off, notification and lock-screen transport controls, and
behaviour across a process death while playing. All of it is written; none of it is proven.

**There are no golden audio fixtures yet.** They belong with the analysis pipeline in Milestone 4,
along with the evaluation metrics — weighted chord-symbol recall, root and quality accuracy,
boundary accuracy, short-chord retention. A model change must not be accepted on subjective
impressions, so that harness has to exist before any model is integrated.

## Conventions

Test names are sentences describing the behaviour, not the method under test:
`` `a chord region is half open at its end` ``, not `testChordAt`.

Tests assert on behaviour a user could notice. Where a test needs a reason to exist that is not
obvious from its name — why a boundary is half-open, why a dash means a flat sign and not minor — the
reason goes in a comment, because that is the part a future reader will otherwise get wrong.

Fakes over mocks. `FakePlaybackController` is a working player with no audio in it; the fake
repositories are working stores backed by a `MutableStateFlow`. Tests that need a database use a real
in-memory Room instance rather than a mocked DAO, because the point is usually to prove that a
cascade or a foreign key does what is expected.

Flow tests use Turbine and wait for the emission that matters rather than counting emissions — a
`combine` over several sources emits once per upstream change, so the first emission after an action
often carries some of the old state.
