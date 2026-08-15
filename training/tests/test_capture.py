"""The layout decides what the score means, so it has to be provably right.

Every test here is about one of two ways this could produce a plausible number for a comparison
that never happened: a truth span that covers time the chord was not sounding, and a chord written
into the truth file as something other than what was played.
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from hearsay_training import capture, harte  # noqa: E402


def chord_json(letter="C", alteration=0, quality="MAJOR", seventh="NONE", sixth=False, suspensions=()):
    return {
        "root": {"letter": letter, "alteration": alteration},
        "quality": quality,
        "seventh": seventh,
        "sixth": sixth,
        "extensions": [],
        "alterations": [],
        "suspensions": list(suspensions),
        "additions": [],
        "omissions": [],
        "bass": None,
    }


def take(take_id="t", notes=((60, 90, 0, 1000),), **chord_arguments):
    return capture.Take(
        id=take_id,
        block="CORE",
        chord=chord_json(**chord_arguments),
        voicing="CLOSE",
        inversion=0,
        extra_intervals=[],
        notes=[capture.Note(*note) for note in notes],
    )


class LabelTest(unittest.TestCase):
    def test_structured_chord_becomes_a_harte_label_that_parses_back(self):
        cases = {
            ("C", 0, "MAJOR", "MAJOR"): ("C:maj7", 0, "maj7"),
            ("E", -1, "MINOR", "MINOR"): ("Eb:min7", 3, "min7"),
            ("F", 1, "DIMINISHED", "DIMINISHED"): ("F#:dim7", 6, "dim7"),
            ("B", 0, "DIMINISHED", "MINOR"): ("B:hdim7", 11, "hdim7"),
            ("G", 0, "MAJOR", "MINOR"): ("G:dom7", 7, "dom7"),
            ("A", 0, "AUGMENTED", "NONE"): ("A:aug", 9, "aug"),
        }
        for (letter, alteration, quality, seventh), (label, root, reduced) in cases.items():
            with self.subTest(label=label):
                chord = chord_json(letter, alteration, quality, seventh)
                self.assertEqual(label, harte.label_of_structured(chord))
                parsed = harte.parse(label)
                self.assertIsNotNone(parsed)
                self.assertEqual(root, parsed.root)
                self.assertEqual(reduced, parsed.quality)

    def test_every_label_it_writes_survives_a_round_trip_through_the_evaluator(self):
        # The evaluator reduces the chart side with the same function; if a label this module
        # writes did not parse back to that reduction, every score would be silently wrong.
        for quality in harte.QUALITIES:
            for root in range(12):
                label = f"{harte.class_label(root + harte.QUALITIES.index(quality) * 12)}"
                parsed = harte.parse(label)
                self.assertIsNotNone(parsed, label)
                self.assertEqual(quality, parsed.quality)

    def test_a_sixth_is_not_written_as_a_triad(self):
        self.assertEqual("C:maj6", harte.label_of_structured(chord_json(sixth=True)))
        self.assertEqual(
            "C:min6", harte.label_of_structured(chord_json(quality="MINOR", sixth=True))
        )

    def test_a_suspension_keeps_which_one_it_was(self):
        self.assertEqual(
            "C:sus2", harte.label_of_structured(chord_json(quality="SUSPENDED", suspensions=[2]))
        )
        self.assertEqual(
            "C:sus4", harte.label_of_structured(chord_json(quality="SUSPENDED", suspensions=[4]))
        )

    def test_a_power_chord_refuses_to_be_spelled_rather_than_claiming_a_third(self):
        # Harte's "5" shorthand reads back as "maj". Writing it would hand the analyzer a major
        # third the player never played, and score it as correct.
        power = chord_json(quality="POWER")
        self.assertIsNone(harte.label_of_structured(power))
        self.assertEqual(harte.POWER, harte.quality_of_structured(power))


class PlacementTest(unittest.TestCase):
    def test_the_truth_span_is_only_where_every_note_sounds_at_once(self):
        # A rolled chord: the bass lands 300 ms before the top note, and the bass comes up first.
        rolled = take(notes=((48, 80, 1000, 2600), (64, 80, 1300, 2900)))
        placed = capture.place(rolled, slot_start_ms=5000, slot_ms=2500)
        self.assertIsNotNone(placed)
        self.assertEqual(5300, placed.truth_start_ms)  # when the last note arrived
        self.assertEqual(6600, placed.truth_end_ms)  # when the first note left
        self.assertEqual(5000, placed.sounding_start_ms)

    def test_a_take_whose_notes_never_overlap_is_refused(self):
        arpeggio = take(notes=((48, 80, 0, 200), (55, 80, 400, 600)))
        self.assertIsNone(capture.place(arpeggio, slot_start_ms=0, slot_ms=2500))

    def test_relative_onsets_are_preserved_rather_than_quantized(self):
        placed = capture.place(
            take(notes=((48, 80, 7000, 8500), (52, 90, 7120, 8500), (55, 70, 7240, 8500))),
            slot_start_ms=1000,
            slot_ms=2500,
        )
        self.assertEqual([1000, 1120, 1240], [note.start_ms for note in placed.notes])
        self.assertEqual([80, 90, 70], [note.velocity for note in placed.notes])

    def test_a_note_held_past_its_slot_is_clipped_so_it_cannot_bleed_into_the_next_label(self):
        placed = capture.place(take(notes=((60, 90, 0, 9000),)), slot_start_ms=0, slot_ms=2500)
        self.assertEqual(2500, placed.notes[0].end_ms)
        self.assertEqual(2500, placed.truth_end_ms)


class PlanTest(unittest.TestCase):
    def test_takes_are_laid_out_one_per_slot_after_the_lead_in(self):
        takes = [take(f"t{index}", notes=((60, 90, 0, 1200),)) for index in range(3)]
        sheets, _ = capture.plan(takes, slot_ms=2000, takes_per_sheet=10, lead_in_ms=1500)
        self.assertEqual(1, len(sheets))
        self.assertEqual([1500, 3500, 5500], [t.sounding_start_ms for t in sheets[0].takes])

    def test_the_corpus_is_split_into_sheets_of_the_requested_size(self):
        takes = [take(f"t{index}") for index in range(25)]
        sheets, _ = capture.plan(takes, takes_per_sheet=10)
        self.assertEqual([10, 10, 5], [len(sheet.takes) for sheet in sheets])
        self.assertEqual(["sheet-001", "sheet-002", "sheet-003"], [s.name for s in sheets])

    def test_the_sheet_runs_a_full_slot_past_the_last_onset(self):
        # The last chord's decay has to be inside the file, or the analyzer reports it short.
        sheets, _ = capture.plan([take()], slot_ms=2500, lead_in_ms=1500)
        self.assertEqual(4000, sheets[0].duration_ms)

    def test_unrenderable_takes_are_excluded_by_name_and_never_silently(self):
        takes = [
            take("good"),
            take("power", quality="POWER"),
            capture.Take("empty", "CORE", chord_json(), "CLOSE", 0, [], []),
            take("arpeggio", notes=((48, 80, 0, 100), (55, 80, 500, 600))),
        ]
        sheets, excluded = capture.plan(takes)
        self.assertEqual(["good"], [t.take_id for t in sheets[0].takes])
        self.assertEqual(["power"], excluded.unspellable)
        self.assertEqual(["empty"], excluded.empty)
        self.assertEqual(["arpeggio"], excluded.never_sounded_together)
        self.assertEqual(3, excluded.total)
        self.assertEqual(3, len(excluded.describe()))


class TruthFileTest(unittest.TestCase):
    def lines(self, sheet, **arguments):
        return [
            line for line in capture.truth_lines(sheet, **arguments) if not line.startswith("#")
        ]

    def test_spans_are_written_in_seconds_in_time_order(self):
        takes = [take(f"t{index}", notes=((60, 90, 0, 1200),)) for index in range(2)]
        sheets, _ = capture.plan(takes, slot_ms=2000, lead_in_ms=1000)
        self.assertEqual(
            ["1.000 2.200 C:maj", "3.000 4.200 C:maj"], self.lines(sheets[0])
        )

    def test_no_span_is_written_over_the_silence_unless_it_is_asked_for(self):
        sheets, _ = capture.plan([take(notes=((60, 90, 0, 500),))], slot_ms=4000, lead_in_ms=1000)
        self.assertEqual(1, len(self.lines(sheets[0])))

    def test_labeled_gaps_keep_clear_of_the_decay_either_side(self):
        takes = [take(f"t{index}", notes=((60, 90, 0, 1000),)) for index in range(2)]
        sheets, _ = capture.plan(takes, slot_ms=4000, lead_in_ms=2000)
        lines = self.lines(sheets[0], label_gaps=True, gap_guard_ms=400)
        # Chord one sounds 2.0–3.0 and chord two 6.0–7.0, in a file 10 s long.
        self.assertEqual(
            [
                "0.400 1.600 N",
                "2.000 3.000 C:maj",
                "3.400 5.600 N",
                "6.000 7.000 C:maj",
                "7.400 9.600 N",
            ],
            lines,
        )

    def test_every_written_span_is_readable_by_the_evaluator(self):
        sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
        import evaluate_chart

        takes = [
            take("a", notes=((60, 90, 0, 1200),), quality="MINOR", seventh="MINOR"),
            take("b", notes=((61, 90, 0, 1200),), letter="E", alteration=-1, sixth=True),
        ]
        sheets, _ = capture.plan(takes)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "sheet.lab"
            path.write_text("\n".join(capture.truth_lines(sheets[0])) + "\n", encoding="utf-8")
            spans, readable, unreadable = evaluate_chart.read_truth(path)
        self.assertEqual(0, unreadable)
        self.assertEqual(2, readable)
        self.assertEqual([(0, "min7"), (3, "maj6")], [(s.root, s.quality) for s in spans])


class ReadTakesTest(unittest.TestCase):
    def write(self, lines):
        directory = tempfile.mkdtemp()
        path = Path(directory) / "takes.jsonl"
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return path

    def row(self, take_id="core-C-maj"):
        return json.dumps(
            {
                "id": take_id,
                "block": "CORE",
                "chord": chord_json(),
                "voicing": "CLOSE",
                "inversion": 0,
                "extraIntervals": [],
                "notes": [{"pitch": 60, "velocity": 90, "onMs": 100, "offMs": 1300}],
            }
        )

    def test_a_take_round_trips_out_of_the_capture_format(self):
        takes, unreadable = capture.read_takes(self.write([self.row()]))
        self.assertEqual(0, unreadable)
        self.assertEqual("core-C-maj", takes[0].id)
        self.assertEqual("C:maj", takes[0].label)
        self.assertEqual([capture.Note(60, 90, 100, 1300)], takes[0].notes)

    def test_a_truncated_last_line_costs_that_take_and_nothing_else(self):
        # A session that ends in a closed lid leaves half a line behind. Losing the hour of
        # playing in front of it would be the worse failure.
        takes, unreadable = capture.read_takes(
            self.write([self.row("a"), self.row("b"), '{"id": "c", "notes": [{"pitc'])
        )
        self.assertEqual(["a", "b"], [t.id for t in takes])
        self.assertEqual(1, unreadable)

    def test_blank_lines_are_not_counted_as_damage(self):
        takes, unreadable = capture.read_takes(self.write([self.row(), "", "  "]))
        self.assertEqual(1, len(takes))
        self.assertEqual(0, unreadable)


if __name__ == "__main__":
    unittest.main()
