#!/usr/bin/env python3
"""Scores an analyzed chart against ground-truth chord labels, component by component.

    python3 evaluate_chart.py --chart song.hearsay.json --truth song.lab
    python3 evaluate_chart.py --chart a.json --truth a.lab --chart b.json --truth b.lab

The chart is the analyzer's JSON export, which carries structured chords — root, quality,
seventh, extensions — so nothing here ever parses a rendered symbol back. The truth file is
Harte-notation ``.lab`` (``start end C:maj7`` per line), the format every published annotation
set and the app's own corrections exporter use.

Scoring is time-weighted over the span the truth file covers, at four tiers that each add one
claim: root; thirds (root + major/minor/suspended family); sevenths (thirds + seventh type);
exact (the full 13-quality vocabulary the training pipeline shares with the app). A chord that
is right about more earns more, and a tier's score can only be lost by being wrong about that
tier's claim — the shape "as complex as the evidence allows, never more" needs exactly this
breakdown to be measurable.

Unreadable truth labels are counted and *excluded*, never silently treated as no-chord: an
evaluator that maps garbage to a legal answer produces plausible scores instead of failures,
which is how a broken serializer goes unnoticed. If more than a tenth of the truth is
unreadable, the run fails loudly.

Kept dependency-free on purpose, like the notation module it builds on: scores must be
computable anywhere, with nothing installed.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hearsay_training import harte  # noqa: E402

#: Predicted boundaries this close to a truth boundary count as found.
DEFAULT_TOLERANCE_MS = 150

#: Above this share of unreadable truth lines, the truth file is broken, not merely imperfect.
MAX_UNREADABLE_FRACTION = 0.10

THIRD_FAMILY = {
    "maj": "maj", "maj7": "maj", "dom7": "maj", "maj6": "maj", "aug": "maj",
    "min": "min", "min7": "min", "min6": "min", "dim": "min", "dim7": "min", "hdim7": "min",
    "sus2": "sus", "sus4": "sus",
    # A power chord claims no third: root credit, never thirds/exact against maj or min truth.
    # (The truth-side harte SHORTHAND still reads "5" as "maj"; that wart lives in harte.py.)
    "5": "power",
}

SEVENTH_TYPE = {
    "dom7": "min7", "min7": "min7", "hdim7": "min7",
    "maj7": "maj7",
    "dim7": "dim7",
    "maj": "none", "min": "none", "dim": "none", "aug": "none",
    "sus2": "none", "sus4": "none", "maj6": "none", "min6": "none",
    "5": "none",
}


@dataclass(frozen=True)
class Span:
    start_ms: int
    end_ms: int
    root: int | None
    quality: str | None

    @property
    def is_chord(self) -> bool:
        return self.root is not None and self.quality is not None


def sorted_or_warn(spans: list[Span], path: Path) -> list[Span]:
    """Sorts spans by start; warns on overlap, because predicted_at scores overlapping input
    by list position and that must never happen silently. Overlaps are not resolved here."""
    ordered = sorted(spans, key=lambda span: span.start_ms)
    for before, after in zip(ordered, ordered[1:]):
        if after.start_ms < before.end_ms:
            print(f"warning: overlapping spans in {path}; first containing span wins",
                  file=sys.stderr)
            break
    return ordered


def read_truth(path: Path) -> tuple[list[Span], int, int]:
    """Reads a .lab file into spans, returning (spans, readable_lines, unreadable_lines)."""
    spans: list[Span] = []
    readable = 0
    unreadable = 0
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        # Only blank lines and comments may skip silently; every other malformed line must
        # count toward MAX_UNREADABLE_FRACTION, or a broken serializer scores plausibly.
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if len(parts) < 3:
            unreadable += 1
            continue
        try:
            start_ms = round(float(parts[0]) * 1000)
            end_ms = round(float(parts[1]) * 1000)
        except ValueError:
            unreadable += 1
            continue
        if end_ms <= start_ms:
            unreadable += 1
            continue
        label = " ".join(parts[2:]).strip()
        # "X" (Isophonics) marks sounding but unclassifiable harmony — not silence, which is
        # "N". It is a legitimate annotation, so it reads fine, but it yields no span: the
        # region is excluded from scoring rather than graded as no-chord.
        if label == "X":
            readable += 1
            continue
        if label in {harte.NO_CHORD, "&pause"}:
            spans.append(Span(start_ms, end_ms, None, None))
            readable += 1
            continue
        parsed = harte.parse(label)
        if parsed is None:
            unreadable += 1
            continue
        spans.append(Span(start_ms, end_ms, parsed.root, parsed.quality))
        readable += 1
    return sorted_or_warn(spans, path), readable, unreadable


def chart_quality(chord: dict) -> str | None:
    """Maps the export's structured components onto the shared 13-quality vocabulary."""
    quality = chord.get("quality")
    seventh = chord.get("seventh")
    sixth = chord.get("sixth", False)
    suspensions = set(chord.get("suspensions") or [])

    if quality == "MAJOR":
        if seventh == "MINOR":
            return "dom7"
        if seventh == "MAJOR":
            return "maj7"
        return "maj6" if sixth else "maj"
    if quality == "MINOR":
        if seventh == "MINOR":
            return "min7"
        if seventh == "MAJOR":
            # min-maj7 has no name in the 13-quality vocabulary; keep the triad claim and
            # drop the seventh rather than granting a flat-seventh credit the chord never
            # earned — exactly how DIMINISHED + MAJOR falls through to "dim".
            return "min"
        return "min6" if sixth else "min"
    if quality == "DIMINISHED":
        if seventh == "DIMINISHED":
            return "dim7"
        if seventh == "MINOR":
            return "hdim7"
        return "dim"
    if quality == "SUSPENDED":
        return "sus2" if 2 in suspensions and 4 not in suspensions else "sus4"
    if quality == "AUGMENTED":
        return "aug"
    if quality == "POWER":
        # A power chord claims a root and a fifth, nothing more; "maj" would stake a claim
        # to a third the chord never made.
        return "5"
    return None


def read_chart(path: Path) -> tuple[list[Span], list[dict]]:
    """Reads the analyzer's JSON export into spans plus its beat list."""
    document = json.loads(path.read_text(encoding="utf-8"))
    spans: list[Span] = []
    for event in document.get("chords", []):
        chord = event.get("chord")
        if chord is None:
            spans.append(Span(event["startMs"], event["endMs"], None, None))
            continue
        root = chord.get("root") or {}
        pitch = harte.parse_note(str(root.get("letter", "")))
        if pitch is None:
            spans.append(Span(event["startMs"], event["endMs"], None, None))
            continue
        pitch = (pitch + int(root.get("alteration", 0))) % 12
        spans.append(Span(event["startMs"], event["endMs"], pitch, chart_quality(chord)))
    return sorted_or_warn(spans, path), document.get("beats", [])


def predicted_at(spans: list[Span], at_ms: int) -> Span | None:
    for span in spans:
        if span.start_ms <= at_ms < span.end_ms:
            return span
    return None


@dataclass
class Tally:
    """Time-weighted agreement, one counter per tier plus no-chord bookkeeping."""

    chord_ms: int = 0
    root_ms: int = 0
    thirds_ms: int = 0
    sevenths_ms: int = 0
    exact_ms: int = 0
    truth_silent_ms: int = 0
    silent_agreed_ms: int = 0
    predicted_silent_over_chord_ms: int = 0

    def rate(self, value: int) -> float:
        return value / self.chord_ms if self.chord_ms else 0.0


def score_pair(truth: list[Span], predicted: list[Span], tally: Tally) -> None:
    """Accumulates overlap-weighted agreement between one truth timeline and one chart."""
    edges = sorted(
        {span.start_ms for span in truth} | {span.end_ms for span in truth}
        | {span.start_ms for span in predicted} | {span.end_ms for span in predicted}
    )
    for begin, end in zip(edges, edges[1:]):
        weight = end - begin
        truth_span = predicted_at(truth, begin)
        if truth_span is None:
            continue  # outside the annotated region: not evaluated, either way
        guess = predicted_at(predicted, begin)

        if not truth_span.is_chord:
            self_silent = guess is None or not guess.is_chord
            tally.truth_silent_ms += weight
            if self_silent:
                tally.silent_agreed_ms += weight
            continue

        tally.chord_ms += weight
        if guess is None or not guess.is_chord:
            tally.predicted_silent_over_chord_ms += weight
            continue
        if guess.root != truth_span.root:
            continue
        tally.root_ms += weight
        if THIRD_FAMILY.get(guess.quality) != THIRD_FAMILY.get(truth_span.quality):
            continue
        tally.thirds_ms += weight
        if SEVENTH_TYPE.get(guess.quality) != SEVENTH_TYPE.get(truth_span.quality):
            continue
        tally.sevenths_ms += weight
        if guess.quality == truth_span.quality:
            tally.exact_ms += weight


def boundary_f(truth: list[Span], predicted: list[Span], tolerance_ms: int) -> tuple[float, float, float]:
    """Precision, recall and F-measure of predicted change points against truth change points."""
    def changes(spans: list[Span]) -> list[int]:
        ordered = sorted(spans, key=lambda span: span.start_ms)
        return [
            after.start_ms
            for before, after in zip(ordered, ordered[1:])
            if (before.root, before.quality) != (after.root, after.quality)
        ]

    truth_changes = changes(truth)
    predicted_changes = changes(predicted)
    # A one-chord song predicted as one chord is perfect agreement, not a zero — main()
    # averages per song, so a 0 here would drag corpus scores down for flawless charts.
    if not truth_changes and not predicted_changes:
        return 1.0, 1.0, 1.0
    if not truth_changes:
        return 0.0, 1.0, 0.0
    if not predicted_changes:
        return 1.0, 0.0, 0.0

    # One-to-one greedy nearest matching: a single predicted boundary must never satisfy
    # two truth boundaries, or vice versa.
    used_predictions: set[int] = set()
    matched = 0
    for t in truth_changes:
        best_index = None
        best_distance = tolerance_ms + 1
        for index, p in enumerate(predicted_changes):
            if index in used_predictions:
                continue
            distance = abs(p - t)
            if distance <= tolerance_ms and distance < best_distance:
                best_index = index
                best_distance = distance
        if best_index is not None:
            used_predictions.add(best_index)
            matched += 1
    recall = matched / len(truth_changes)
    precision = matched / len(predicted_changes)
    f_measure = (
        2 * precision * recall / (precision + recall) if precision + recall else 0.0
    )
    return precision, recall, f_measure


def beat_histogram(predicted: list[Span], beats: list[dict]) -> dict[int, int]:
    """Where chord changes land in the bar — the cheapest health metric there is.

    A working analysis peaks on beats one and three. Flat means the changes are noise or the
    grid is sliding; peaked on two and four means the downbeat is a beat out.
    """
    histogram: dict[int, int] = {}
    if not beats:
        return histogram
    # Same identity rule as boundary_f: a no-chord row or a same-chord split row is not a
    # chord start, and counting it would let noise rows shape the histogram.
    ordered = sorted(predicted, key=lambda span: span.start_ms)
    for before, after in zip(ordered, ordered[1:]):
        if not after.is_chord:
            continue
        if (before.root, before.quality) == (after.root, after.quality):
            continue
        nearest = min(beats, key=lambda beat: abs(beat["timeMs"] - after.start_ms))
        position = nearest.get("beatInMeasure", 0)
        histogram[position] = histogram.get(position, 0) + 1
    return histogram


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chart", type=Path, action="append", required=True)
    parser.add_argument("--truth", type=Path, action="append", required=True)
    parser.add_argument("--tolerance-ms", type=int, default=DEFAULT_TOLERANCE_MS)
    parser.add_argument("--json", type=Path, help="also write the scores as JSON")
    args = parser.parse_args()

    if len(args.chart) != len(args.truth):
        parser.error("--chart and --truth must be given the same number of times, in pairs")

    tally = Tally()
    boundary_scores: list[tuple[float, float, float]] = []
    histogram: dict[int, int] = {}
    unreadable_total = 0
    readable_total = 0

    for chart_path, truth_path in zip(args.chart, args.truth):
        truth, readable, unreadable = read_truth(truth_path)
        readable_total += readable
        unreadable_total += unreadable
        predicted, beats = read_chart(chart_path)
        score_pair(truth, predicted, tally)
        boundary_scores.append(boundary_f(truth, predicted, args.tolerance_ms))
        for position, count in beat_histogram(predicted, beats).items():
            histogram[position] = histogram.get(position, 0) + count

    total_lines = readable_total + unreadable_total
    if total_lines == 0:
        print(
            "REFUSING TO SCORE: the truth files contained nothing readable at all. That is "
            "a broken input, not a zero score."
        )
        return 1
    if unreadable_total / total_lines > MAX_UNREADABLE_FRACTION:
        print(
            f"REFUSING TO SCORE: {unreadable_total} of {total_lines} truth labels were "
            "unreadable. That is a broken file or serializer, and scoring around it would "
            "produce a plausible number for a comparison that never happened."
        )
        return 1

    precision = sum(score[0] for score in boundary_scores) / len(boundary_scores)
    recall = sum(score[1] for score in boundary_scores) / len(boundary_scores)
    f_measure = sum(score[2] for score in boundary_scores) / len(boundary_scores)

    results = {
        "songs": len(args.chart),
        "evaluatedChordSeconds": tally.chord_ms / 1000,
        "rootAccuracy": tally.rate(tally.root_ms),
        "thirdsAccuracy": tally.rate(tally.thirds_ms),
        "seventhsAccuracy": tally.rate(tally.sevenths_ms),
        "exactAccuracy": tally.rate(tally.exact_ms),
        "missedChordFraction": tally.rate(tally.predicted_silent_over_chord_ms),
        "noChordAgreement": (
            tally.silent_agreed_ms / tally.truth_silent_ms if tally.truth_silent_ms else None
        ),
        "boundaryPrecision": precision,
        "boundaryRecall": recall,
        "boundaryF": f_measure,
        "beatPositionHistogram": {str(k): v for k, v in sorted(histogram.items())},
        "unreadableTruthLabels": unreadable_total,
    }

    print(f"Scored {results['songs']} song(s), {results['evaluatedChordSeconds']:.0f}s of annotated harmony")
    print(f"  root     {results['rootAccuracy']:6.1%}")
    print(f"  thirds   {results['thirdsAccuracy']:6.1%}   (root + major/minor/sus family)")
    print(f"  sevenths {results['seventhsAccuracy']:6.1%}   (thirds + seventh type)")
    print(f"  exact    {results['exactAccuracy']:6.1%}   (full shared vocabulary)")
    print(f"  boundaries: precision {precision:.1%}  recall {recall:.1%}  F {f_measure:.1%}"
          f"  (tolerance {args.tolerance_ms} ms)")
    if results["noChordAgreement"] is not None:
        print(f"  no-chord agreement {results['noChordAgreement']:.1%}")
    if results["missedChordFraction"]:
        print(f"  blank over annotated harmony {results['missedChordFraction']:.1%}")
    if histogram:
        total = sum(histogram.values())
        shape = "  ".join(f"{k}:{v / total:.0%}" for k, v in sorted(histogram.items()))
        print(f"  chord starts by beat position: {shape}")
    if unreadable_total:
        print(f"  ({unreadable_total} unreadable truth labels excluded)")

    if args.json:
        args.json.write_text(json.dumps(results, indent=2), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
