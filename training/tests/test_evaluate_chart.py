"""The evaluator itself has to be provably right, or every number it prints is decoration."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import evaluate_chart  # noqa: E402
from evaluate_chart import Span, Tally, boundary_f, chart_quality, score_pair  # noqa: E402


def chord(start_ms, end_ms, root, quality):
    return Span(start_ms, end_ms, root, quality)


def silence(start_ms, end_ms):
    return Span(start_ms, end_ms, None, None)


class TierScoringTest(unittest.TestCase):
    def score(self, truth, predicted):
        tally = Tally()
        score_pair(truth, predicted, tally)
        return tally

    def test_perfect_agreement_scores_one_at_every_tier(self):
        truth = [chord(0, 1000, 0, "maj7"), chord(1000, 2000, 9, "min7")]
        tally = self.score(truth, list(truth))
        self.assertEqual(2000, tally.chord_ms)
        for value in (tally.root_ms, tally.thirds_ms, tally.sevenths_ms, tally.exact_ms):
            self.assertEqual(2000, value)

    def test_each_tier_loses_exactly_its_own_claim(self):
        truth = [chord(0, 1000, 0, "maj7")]

        # Wrong root: everything falls.
        tally = self.score(truth, [chord(0, 1000, 1, "maj7")])
        self.assertEqual(0, tally.root_ms)

        # Right root, wrong third family: root holds, the rest falls.
        tally = self.score(truth, [chord(0, 1000, 0, "min7")])
        self.assertEqual(1000, tally.root_ms)
        self.assertEqual(0, tally.thirds_ms)

        # Right family, wrong seventh: thirds holds. A dominant against a maj7 is this case.
        tally = self.score(truth, [chord(0, 1000, 0, "dom7")])
        self.assertEqual(1000, tally.thirds_ms)
        self.assertEqual(0, tally.sevenths_ms)

        # Right seventh type, different exact quality: dom7 vs min7 share the flat seventh but
        # differ in family, so use maj6 vs maj: same family, same absent seventh, different name.
        tally = self.score(truth, [chord(0, 1000, 0, "maj")])
        self.assertEqual(1000, tally.thirds_ms)
        self.assertEqual(0, tally.sevenths_ms)  # maj7's seventh vs none

    def test_time_weighting_uses_overlap_not_event_count(self):
        truth = [chord(0, 3000, 0, "maj")]
        predicted = [chord(0, 1000, 0, "maj"), chord(1000, 3000, 7, "maj")]
        tally = self.score(truth, predicted)
        self.assertEqual(3000, tally.chord_ms)
        self.assertEqual(1000, tally.root_ms)  # one of three seconds right

    def test_truth_silence_is_scored_separately_not_as_a_chord(self):
        truth = [silence(0, 1000), chord(1000, 2000, 0, "maj")]
        predicted = [chord(0, 1000, 0, "maj"), chord(1000, 2000, 0, "maj")]
        tally = self.score(truth, predicted)
        self.assertEqual(1000, tally.chord_ms)
        self.assertEqual(1000, tally.truth_silent_ms)
        self.assertEqual(0, tally.silent_agreed_ms)  # we played through their rest

    def test_a_blank_prediction_over_real_harmony_is_a_miss_not_a_crash(self):
        truth = [chord(0, 1000, 0, "maj")]
        tally = self.score(truth, [silence(0, 1000)])
        self.assertEqual(1000, tally.predicted_silent_over_chord_ms)
        self.assertEqual(0, tally.root_ms)


class BoundaryTest(unittest.TestCase):
    def test_boundaries_within_tolerance_count(self):
        truth = [chord(0, 1000, 0, "maj"), chord(1000, 2000, 7, "maj")]
        predicted = [chord(0, 1100, 0, "maj"), chord(1100, 2000, 7, "maj")]
        precision, recall, f_measure = boundary_f(truth, predicted, tolerance_ms=150)
        self.assertEqual(1.0, precision)
        self.assertEqual(1.0, recall)
        self.assertEqual(1.0, f_measure)

    def test_boundaries_outside_tolerance_do_not(self):
        truth = [chord(0, 1000, 0, "maj"), chord(1000, 2000, 7, "maj")]
        predicted = [chord(0, 1400, 0, "maj"), chord(1400, 2000, 7, "maj")]
        _, recall, _ = boundary_f(truth, predicted, tolerance_ms=150)
        self.assertEqual(0.0, recall)

    def test_a_boundary_needs_an_identity_change_not_just_a_new_row(self):
        # Two consecutive rows with the same chord are one chord; splitting them must not
        # manufacture a boundary that gets credit.
        truth = [chord(0, 1000, 0, "maj"), chord(1000, 2000, 7, "maj")]
        predicted = [
            chord(0, 500, 0, "maj"),
            chord(500, 1000, 0, "maj"),
            chord(1000, 2000, 7, "maj"),
        ]
        precision, recall, _ = boundary_f(truth, predicted, tolerance_ms=150)
        self.assertEqual(1.0, precision)
        self.assertEqual(1.0, recall)


class ChartQualityTest(unittest.TestCase):
    def test_the_export_components_map_onto_the_shared_vocabulary(self):
        cases = [
            ({"quality": "MAJOR", "seventh": "NONE", "sixth": False}, "maj"),
            ({"quality": "MAJOR", "seventh": "MINOR"}, "dom7"),
            ({"quality": "MAJOR", "seventh": "MAJOR"}, "maj7"),
            ({"quality": "MAJOR", "seventh": "NONE", "sixth": True}, "maj6"),
            ({"quality": "MINOR", "seventh": "NONE"}, "min"),
            ({"quality": "MINOR", "seventh": "MINOR"}, "min7"),
            ({"quality": "DIMINISHED", "seventh": "NONE"}, "dim"),
            ({"quality": "DIMINISHED", "seventh": "MINOR"}, "hdim7"),
            ({"quality": "DIMINISHED", "seventh": "DIMINISHED"}, "dim7"),
            ({"quality": "SUSPENDED", "seventh": "NONE", "suspensions": [4]}, "sus4"),
            ({"quality": "SUSPENDED", "seventh": "NONE", "suspensions": [2]}, "sus2"),
            ({"quality": "AUGMENTED", "seventh": "NONE"}, "aug"),
        ]
        for components, expected in cases:
            self.assertEqual(expected, chart_quality(components), components)


class EndToEndTest(unittest.TestCase):
    def test_reads_real_files_and_scores_them(self):
        with tempfile.TemporaryDirectory() as tmp:
            truth_path = Path(tmp) / "song.lab"
            truth_path.write_text("0.0 1.0 C:maj\n1.0 2.0 A:min7\n", encoding="utf-8")

            chart_path = Path(tmp) / "song.hearsay.json"
            chart_path.write_text(json.dumps({
                "chords": [
                    {
                        "startMs": 0, "endMs": 1000,
                        "chord": {"root": {"letter": "C", "alteration": 0},
                                  "quality": "MAJOR", "seventh": "NONE", "sixth": False},
                    },
                    {
                        "startMs": 1000, "endMs": 2000,
                        "chord": {"root": {"letter": "A", "alteration": 0},
                                  "quality": "MINOR", "seventh": "MINOR", "sixth": False},
                    },
                ],
                "beats": [{"timeMs": 0, "beatInMeasure": 1, "measureNumber": 1},
                          {"timeMs": 1000, "beatInMeasure": 2, "measureNumber": 1}],
            }), encoding="utf-8")

            truth, readable, unreadable = evaluate_chart.read_truth(truth_path)
            self.assertEqual(2, readable)
            self.assertEqual(0, unreadable)
            predicted, beats = evaluate_chart.read_chart(chart_path)
            self.assertEqual(2, len(predicted))
            self.assertEqual(2, len(beats))

            tally = Tally()
            score_pair(truth, predicted, tally)
            self.assertEqual(2000, tally.exact_ms)

    def test_unreadable_truth_is_counted_never_silently_absorbed(self):
        with tempfile.TemporaryDirectory() as tmp:
            truth_path = Path(tmp) / "bad.lab"
            truth_path.write_text("0.0 1.0 C:maj\n1.0 2.0 utter-nonsense\n", encoding="utf-8")
            spans, readable, unreadable = evaluate_chart.read_truth(truth_path)
            self.assertEqual(1, readable)
            self.assertEqual(1, unreadable)
            # The nonsense span is excluded from scoring entirely, not mapped to no-chord.
            self.assertEqual(1, len(spans))

    def test_a_sharp_root_survives_the_round_trip(self):
        # The app writes roots as letter + alteration; F#'s pitch class must come back as 6.
        with tempfile.TemporaryDirectory() as tmp:
            chart_path = Path(tmp) / "song.hearsay.json"
            chart_path.write_text(json.dumps({
                "chords": [{
                    "startMs": 0, "endMs": 1000,
                    "chord": {"root": {"letter": "F", "alteration": 1},
                              "quality": "MAJOR", "seventh": "NONE", "sixth": False},
                }],
                "beats": [],
            }), encoding="utf-8")
            predicted, _ = evaluate_chart.read_chart(chart_path)
            self.assertEqual(6, predicted[0].root)


if __name__ == "__main__":
    unittest.main()
