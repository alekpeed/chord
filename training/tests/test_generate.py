"""Tests for turning a chord chart into audio and an exact label.

Needs nothing installed. The arithmetic that turns bars into seconds is the highest-risk code
here for the same reason frame alignment is in `dataset`: if it is wrong, every label in the
generated corpus is wrong by the same amount, the model trains happily on it, and the only
symptom is an accuracy number that never quite gets good.

The checks against MMA's actual output are tested with hand-written note lists rather than by
running MMA, so a machine without it still verifies the logic that decides whether a rendering
may be kept.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from hearsay_training import harte  # noqa: E402
from hearsay_training.generate import (  # noqa: E402
    CHORD_TONES,
    MMA_QUALITY,
    Chart,
    Chord,
    degrees_to_chart,
)


class ChordTest(unittest.TestCase):
    def test_covers_every_quality_the_model_predicts(self):
        # A quality the generator cannot ask for is a class the corpus cannot cover, which is the
        # whole reason for generating one.
        self.assertEqual(set(MMA_QUALITY), set(harte.QUALITIES))
        self.assertEqual(set(CHORD_TONES), set(harte.QUALITIES))

    def test_diminished_asks_mma_for_a_triad_not_a_seventh(self):
        # MMA voices `Cdim` as [0, 3, 6, 9]. Measured, not assumed — and if this mapping is ever
        # "corrected" back to "dim", every diminished label in the corpus becomes a lie.
        self.assertEqual(Chord(root=0, quality="dim").mma, "Cmb5")
        self.assertEqual(Chord(root=0, quality="dim7").mma, "Cdim7")

    def test_harte_label_reads_back_as_the_same_chord(self):
        for root in range(12):
            for quality in harte.QUALITIES:
                chord = Chord(root=root, quality=quality)
                parsed = harte.parse(chord.harte)
                self.assertIsNotNone(parsed, chord.harte)
                self.assertEqual(parsed.class_index, chord.class_index, chord.harte)

    def test_transposition_moves_the_root_and_keeps_the_quality(self):
        chord = Chord(root=11, quality="hdim7").transposed(2)
        self.assertEqual(chord.root, 1)
        self.assertEqual(chord.quality, "hdim7")

    def test_refuses_a_quality_outside_the_vocabulary(self):
        with self.assertRaises(ValueError):
            Chord(root=0, quality="maj9")


class ChartTimingTest(unittest.TestCase):
    def chart(self, bars, tempo=120.0):
        return Chart(name="t", tempo=tempo, groove="Swing", bars=bars)

    def test_one_chord_per_bar_at_120_is_two_seconds_each(self):
        chart = self.chart((
            (Chord(0, "maj7"),),
            (Chord(7, "dom7"),),
        ))
        spans = chart.spans()
        self.assertEqual([(s.start, s.end) for s in spans], [(0.0, 2.0), (2.0, 4.0)])
        self.assertEqual(chart.duration, 4.0)

    def test_two_chords_share_the_bar_evenly(self):
        chart = self.chart(((Chord(2, "min7"), Chord(7, "dom7")),))
        spans = chart.spans()
        self.assertEqual([(s.start, s.end) for s in spans], [(0.0, 1.0), (1.0, 2.0)])

    def test_spans_are_contiguous_and_cover_the_whole_chart(self):
        chart = self.chart(
            (
                (Chord(0, "maj"),),
                (Chord(5, "maj"), Chord(7, "dom7")),
                (Chord(0, "maj"),),
            ),
            tempo=93.0,
        )
        spans = chart.spans()
        self.assertAlmostEqual(spans[0].start, 0.0)
        for earlier, later in zip(spans, spans[1:]):
            self.assertAlmostEqual(earlier.end, later.start)
        self.assertAlmostEqual(spans[-1].end, chart.duration)

    def test_lab_text_is_readable_by_the_annotation_reader(self):
        chart = self.chart(((Chord(0, "maj7"),), (Chord(9, "hdim7"),)))
        lines = chart.lab_text().strip().splitlines()
        self.assertEqual(len(lines), 2)
        start, end, label = lines[1].split()
        self.assertAlmostEqual(float(start), 2.0)
        self.assertAlmostEqual(float(end), 4.0)
        self.assertEqual(harte.parse(label).class_index, Chord(9, "hdim7").class_index)

    def test_refuses_a_bar_that_does_not_divide_evenly(self):
        with self.assertRaises(ValueError):
            self.chart(((Chord(0, "maj"), Chord(2, "min"), Chord(4, "min")),))

    def test_mma_text_has_one_numbered_line_per_bar_and_no_repeats(self):
        chart = self.chart(((Chord(0, "maj7"),), (Chord(7, "dom7"),)))
        text = chart.mma_text()
        self.assertIn("Tempo 120", text)
        self.assertIn("Groove Swing", text)
        # Repeats would shift the music against the bar numbers the labels are computed from.
        self.assertNotIn("Repeat", text)
        numbered = [ln for ln in text.splitlines() if ln[:1].isdigit()]
        self.assertEqual(numbered, ["1    Cmaj7", "2    G7"])


class VoicingCheckTest(unittest.TestCase):
    """The check that decides whether a rendering may be kept."""

    def chart(self):
        return Chart(
            name="t", tempo=120.0, groove="Swing",
            bars=((Chord(0, "maj"),), (Chord(0, "dim"),)),
        )

    def test_accepts_a_rendering_that_plays_the_written_chords(self):
        voiced = [(0.5, 0), (0.5, 4), (0.5, 7), (2.5, 0), (2.5, 3), (2.5, 6)]
        self.assertEqual(self.chart().problems_against(voiced), [])

    def test_rejects_a_chord_the_groove_never_played(self):
        # The trap that prompted this check: MMA accepts the chord, the groove does not comp
        # there, and the label describes audio that contains nothing.
        voiced = [(0.5, 0), (0.5, 4), (0.5, 7)]
        problems = self.chart().problems_against(voiced)
        self.assertEqual(len(problems), 1)
        self.assertIn("never voiced", problems[0])

    def test_rejects_a_diminished_triad_voiced_as_a_seventh(self):
        # Exactly what `Cdim` would have produced: the added sixth makes it a different chord.
        voiced = [(0.5, 0), (0.5, 4), (0.5, 7), (2.5, 0), (2.5, 3), (2.5, 6), (2.5, 9)]
        problems = self.chart().problems_against(voiced)
        self.assertEqual(len(problems), 1)
        self.assertIn("does not contain", problems[0])

    def test_a_note_on_the_boundary_is_not_credited_to_the_previous_span(self):
        # A chord landing exactly on its own start must count for it, or every span would look
        # like it was voiced by the chord before it.
        voiced = [(0.5, 0), (0.5, 4), (0.5, 7), (2.0, 0), (2.0, 3), (2.0, 6)]
        self.assertEqual(self.chart().problems_against(voiced), [])


class DegreesTest(unittest.TestCase):
    def test_builds_the_same_progression_in_any_key(self):
        degrees = (((2, "min7"),), ((7, "dom7"),), ((0, "maj7"),))
        in_c = degrees_to_chart("p", 120, "Swing", degrees, key=0)
        in_d = degrees_to_chart("p", 120, "Swing", degrees, key=2)
        self.assertEqual([b[0].harte for b in in_c.bars], ["D:min7", "G:dom7", "C:maj7"])
        self.assertEqual([b[0].harte for b in in_d.bars], ["E:min7", "A:dom7", "D:maj7"])

    def test_the_progression_bank_covers_every_quality(self):
        # Guards the reason this exists: coverage of the classes real corpora are thin on.
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        import generate_corpus

        used = {
            quality
            for _, _, _, degrees in generate_corpus.PROGRESSIONS
            for bar in degrees
            for _, quality in bar
        }
        self.assertEqual(used, set(harte.QUALITIES), f"missing: {set(harte.QUALITIES) - used}")


if __name__ == "__main__":
    unittest.main()
