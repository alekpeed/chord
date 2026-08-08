"""Tests for annotation reading, frame alignment and the pitch-shift trick.

Needs only numpy. Frame alignment is the highest-risk code in the pipeline: an off-by-one here
trains the model on chords that are consistently early or late, and nothing would ever fail —
the loss would just plateau somewhere mediocre and look like a modeling problem.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from hearsay_training import dataset, harte  # noqa: E402


class ReadLabTest(unittest.TestCase):
    def write(self, text: str) -> Path:
        handle = tempfile.NamedTemporaryFile("w", suffix=".lab", delete=False)
        handle.write(text)
        handle.close()
        return Path(handle.name)

    def test_reads_spans_and_labels(self):
        path = self.write("0.0 2.0 C:maj\n2.0 4.0 G:7\n4.0 6.0 N\n")
        spans = dataset.read_lab(path)

        self.assertEqual(3, len(spans))
        self.assertEqual(harte.parse("C:maj").class_index, spans[0].class_index)
        self.assertEqual(harte.parse("G:7").class_index, spans[1].class_index)
        self.assertEqual(harte.NO_CHORD_INDEX, spans[2].class_index)
        self.assertEqual((2.0, 4.0), (spans[1].start, spans[1].end))

    def test_tolerates_tabs_and_extra_whitespace(self):
        path = self.write("0.0\t1.5\tD:min7\n")
        self.assertEqual(harte.parse("D:min7").class_index, dataset.read_lab(path)[0].class_index)

    def test_an_unreadable_label_becomes_no_chord_rather_than_a_gap(self):
        # Dropping the line would shift every frame after it, which is far worse.
        path = self.write("0.0 1.0 C:maj\n1.0 2.0 ???\n2.0 3.0 G:maj\n")
        spans = dataset.read_lab(path)
        self.assertEqual(3, len(spans))
        self.assertEqual(harte.NO_CHORD_INDEX, spans[1].class_index)

    def test_skips_malformed_lines(self):
        path = self.write("nonsense\n0.0 1.0 C:maj\n\n")
        self.assertEqual(1, len(dataset.read_lab(path)))


class FrameAlignmentTest(unittest.TestCase):
    def test_each_frame_takes_the_label_of_the_span_it_falls_in(self):
        spans = [
            dataset.Annotation(0.0, 1.0, 5),
            dataset.Annotation(1.0, 2.0, 9),
        ]
        frames = int(2.0 * dataset.FRAME_RATE)
        labels = dataset.labels_for_frames(spans, frames)

        self.assertEqual(5, labels[0])
        self.assertEqual(9, labels[-1])
        # The change lands within one frame of the annotated boundary.
        boundary = int(1.0 * dataset.FRAME_RATE)
        self.assertTrue(set(labels[:boundary - 1]) == {5})
        self.assertTrue(set(labels[boundary + 1:]) == {9})

    def test_frames_past_the_end_are_no_chord(self):
        spans = [dataset.Annotation(0.0, 1.0, 5)]
        labels = dataset.labels_for_frames(spans, int(3.0 * dataset.FRAME_RATE))
        self.assertEqual(harte.NO_CHORD_INDEX, labels[-1])

    def test_gaps_between_spans_are_no_chord(self):
        spans = [
            dataset.Annotation(0.0, 1.0, 5),
            dataset.Annotation(2.0, 3.0, 9),
        ]
        labels = dataset.labels_for_frames(spans, int(3.0 * dataset.FRAME_RATE))
        middle = int(1.5 * dataset.FRAME_RATE)
        self.assertEqual(harte.NO_CHORD_INDEX, labels[middle])

    def test_no_annotations_means_no_chords(self):
        labels = dataset.labels_for_frames([], 50)
        self.assertTrue((labels == harte.NO_CHORD_INDEX).all())


class PitchShiftTest(unittest.TestCase):
    def test_a_semitone_is_exactly_two_bins(self):
        # This is the whole reason the constant-Q transform is used at 24 bins per octave.
        self.assertEqual(2, dataset.BINS_PER_SEMITONE)

    def test_shifting_up_moves_energy_up_the_spectrum(self):
        features = np.zeros((4, dataset.N_BINS), dtype=np.float32)
        features[:, 10] = 1.0

        shifted = dataset.shift_features(features, 1)

        self.assertEqual(0.0, shifted[0, 10])
        self.assertEqual(1.0, shifted[0, 12])

    def test_bins_rolled_in_from_outside_are_silent_not_wrapped(self):
        # Wrapping would fold the top of the spectrum onto the bass and invent harmony.
        features = np.ones((2, dataset.N_BINS), dtype=np.float32)
        shifted = dataset.shift_features(features, 2)
        self.assertTrue((shifted[:, :4] == 0).all())

        shifted_down = dataset.shift_features(features, -2)
        self.assertTrue((shifted_down[:, -4:] == 0).all())

    def test_shifting_by_zero_changes_nothing(self):
        features = np.random.rand(3, dataset.N_BINS).astype(np.float32)
        np.testing.assert_array_equal(features, dataset.shift_features(features, 0))

    def test_labels_move_with_the_audio(self):
        c_maj = harte.parse("C:maj").class_index
        d_maj = harte.parse("D:maj").class_index
        labels = np.array([c_maj, c_maj, harte.NO_CHORD_INDEX], dtype=np.int64)

        shifted = dataset.shift_labels(labels, 2)

        self.assertEqual(d_maj, shifted[0])
        self.assertEqual(harte.NO_CHORD_INDEX, shifted[2])

    def test_the_augmentation_range_multiplies_the_dataset(self):
        # Eleven keys per song is what turns two hundred songs into a real training set.
        self.assertEqual(11, dataset.AUGMENT_RANGE * 2 + 1)


class AudioMatchingTest(unittest.TestCase):
    def test_normalization_ignores_track_numbers_and_punctuation(self):
        self.assertEqual(
            dataset.normalize_title("01 - A Hard Day's Night"),
            dataset.normalize_title("a_hard_days_night"),
        )

    def test_matches_a_library_file_to_an_annotation(self):
        index = {
            dataset.normalize_title("Norwegian Wood"): Path("/music/norwegian.flac"),
            dataset.normalize_title("Michelle"): Path("/music/michelle.flac"),
        }
        self.assertEqual(
            Path("/music/norwegian.flac"),
            dataset.match_audio("11_-_Norwegian_Wood", index),
        )

    def test_reports_nothing_when_the_recording_is_not_owned(self):
        # The honest outcome: the song is listed as missing rather than matched to the wrong file.
        index = {dataset.normalize_title("Michelle"): Path("/music/michelle.flac")}
        self.assertIsNone(dataset.match_audio("Taxman", index))


if __name__ == "__main__":
    unittest.main()
