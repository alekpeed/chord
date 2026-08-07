"""Tests for Harte-notation parsing.

Run with ``python3 -m unittest discover training/tests`` — no dependencies needed, on purpose.
This is the piece that decides what every training label means, and a silent mistake here
would train the model on wrong answers without anything ever failing.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from hearsay_training import harte  # noqa: E402


class ParseNoteTest(unittest.TestCase):
    def test_natural_notes(self):
        self.assertEqual(0, harte.parse_note("C"))
        self.assertEqual(4, harte.parse_note("E"))
        self.assertEqual(11, harte.parse_note("B"))

    def test_accidentals(self):
        self.assertEqual(1, harte.parse_note("C#"))
        self.assertEqual(10, harte.parse_note("Bb"))
        self.assertEqual(2, harte.parse_note("C##"))
        self.assertEqual(11, harte.parse_note("Cb"))

    def test_rejects_nonsense(self):
        self.assertIsNone(harte.parse_note("H"))
        self.assertIsNone(harte.parse_note(""))
        self.assertIsNone(harte.parse_note("Cx"))


class ParseChordTest(unittest.TestCase):
    def assert_chord(self, label: str, root: int, quality: str):
        parsed = harte.parse(label)
        self.assertIsNotNone(parsed, f"{label} did not parse")
        self.assertEqual(root, parsed.root, label)
        self.assertEqual(quality, parsed.quality, label)

    def test_shorthand(self):
        self.assert_chord("C:maj", 0, "maj")
        self.assert_chord("A:min", 9, "min")
        self.assert_chord("G:7", 7, "dom7")
        self.assert_chord("F:maj7", 5, "maj7")
        self.assert_chord("D:min7", 2, "min7")
        self.assert_chord("B:hdim7", 11, "hdim7")
        self.assert_chord("G#:dim7", 8, "dim7")

    def test_a_bare_root_is_major(self):
        self.assert_chord("C", 0, "maj")
        self.assert_chord("Eb", 3, "maj")

    def test_extensions_reduce_to_their_base_quality(self):
        # The vocabulary has no thirteenths; a G13 is still a dominant chord on G.
        self.assert_chord("G:13", 7, "dom7")
        self.assert_chord("C:maj9", 0, "maj7")
        self.assert_chord("D:min11", 2, "min7")

    def test_parenthesised_extensions_are_ignored(self):
        self.assert_chord("G:7(b9)", 7, "dom7")
        self.assert_chord("C:maj7(9)", 0, "maj7")

    def test_slash_bass_is_read_and_discarded(self):
        # The vocabulary has no inversions, so C/E is a C major chord.
        self.assert_chord("C:maj/3", 0, "maj")
        self.assert_chord("G:7/b7", 7, "dom7")

    def test_spelled_interval_lists(self):
        self.assert_chord("C:(1,b3,5,b7)", 0, "min7")
        self.assert_chord("F:(1,3,5,b7)", 5, "dom7")
        self.assert_chord("D:(1,b3,b5,b7)", 2, "hdim7")
        self.assert_chord("E:(1,4,5)", 4, "sus4")

    def test_no_chord(self):
        self.assertIsNone(harte.parse("N"))
        self.assertIsNone(harte.parse(""))
        self.assertIsNone(harte.parse("X"))

    def test_unreadable_labels_are_refused_rather_than_guessed(self):
        # A wrong guess here would become a training label, which is worse than a dropped frame.
        self.assertIsNone(harte.parse("Q:maj"))
        self.assertIsNone(harte.parse("C:nonsense"))


class ClassIndexTest(unittest.TestCase):
    def test_indices_are_unique_and_in_range(self):
        seen = set()
        for quality_index, quality in enumerate(harte.QUALITIES):
            for root in range(12):
                index = harte.ParsedChord(root, quality).class_index
                self.assertEqual(quality_index * 12 + root, index)
                self.assertTrue(0 <= index < harte.NO_CHORD_INDEX)
                seen.add(index)
        self.assertEqual(harte.NO_CHORD_INDEX, len(seen))

    def test_class_count_matches_the_vocabulary(self):
        self.assertEqual(len(harte.QUALITIES) * 12 + 1, harte.NUM_CLASSES)

    def test_labels_round_trip(self):
        for index in range(harte.NUM_CLASSES):
            label = harte.class_label(index)
            if index == harte.NO_CHORD_INDEX:
                self.assertEqual("N", label)
                continue
            reparsed = harte.parse(label)
            self.assertIsNotNone(reparsed, label)
            self.assertEqual(index, reparsed.class_index, label)


class TransposeTest(unittest.TestCase):
    def test_shifting_moves_the_root_and_keeps_the_quality(self):
        c_min7 = harte.parse("C:min7").class_index
        d_min7 = harte.parse("D:min7").class_index
        self.assertEqual(d_min7, harte.transpose_class(c_min7, 2))

    def test_shifting_wraps_around_the_octave(self):
        b_maj = harte.parse("B:maj").class_index
        c_maj = harte.parse("C:maj").class_index
        self.assertEqual(c_maj, harte.transpose_class(b_maj, 1))
        self.assertEqual(b_maj, harte.transpose_class(c_maj, -1))

    def test_no_chord_does_not_move(self):
        # Transposing silence is still silence; shifting it would invent a chord.
        self.assertEqual(harte.NO_CHORD_INDEX, harte.transpose_class(harte.NO_CHORD_INDEX, 5))

    def test_a_full_octave_is_a_no_op(self):
        for index in range(harte.NUM_CLASSES):
            self.assertEqual(index, harte.transpose_class(index, 12))


class AppSymbolTest(unittest.TestCase):
    def test_renders_the_symbols_the_app_writes(self):
        self.assertEqual("C", harte.to_app_symbol(harte.parse("C:maj").class_index))
        self.assertEqual("Am", harte.to_app_symbol(harte.parse("A:min").class_index))
        self.assertEqual("G7", harte.to_app_symbol(harte.parse("G:7").class_index))
        self.assertEqual("Fmaj7", harte.to_app_symbol(harte.parse("F:maj7").class_index))
        self.assertEqual("Bm7b5", harte.to_app_symbol(harte.parse("B:hdim7").class_index))
        self.assertEqual("N.C.", harte.to_app_symbol(harte.NO_CHORD_INDEX))


if __name__ == "__main__":
    unittest.main()
