"""The renderer makes the audio every score will be computed from.

If it writes the wrong pitch, or writes a chord at the wrong moment, the evaluator downstream
produces a confident number about a comparison that never happened — which is the exact failure
this whole measurement loop exists to end. So the MIDI is checked byte by byte, and the audio is
checked by looking for the pitches inside it rather than by trusting that the synthesizer works.
"""

from __future__ import annotations

import struct
import sys
import tempfile
import unittest
import wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import render_takes  # noqa: E402
from hearsay_training import capture  # noqa: E402

try:
    import numpy
except ImportError:  # pragma: no cover - exercised only where numpy is absent
    numpy = None


def chord_json(letter="C", quality="MAJOR", seventh="NONE"):
    return {
        "root": {"letter": letter, "alteration": 0},
        "quality": quality,
        "seventh": seventh,
        "sixth": False,
        "extensions": [],
        "alterations": [],
        "suspensions": [],
        "additions": [],
        "omissions": [],
        "bass": None,
    }


def take(take_id, pitches, on_ms=0, off_ms=1200, **chord_arguments):
    return capture.Take(
        id=take_id,
        block="CORE",
        chord=chord_json(**chord_arguments),
        voicing="CLOSE",
        inversion=0,
        extra_intervals=[],
        notes=[capture.Note(pitch, 100, on_ms, off_ms) for pitch in pitches],
    )


def read_variable_length(data, index):
    value = 0
    while True:
        byte = data[index]
        index += 1
        value = (value << 7) | (byte & 0x7F)
        if not byte & 0x80:
            return value, index


def parse_midi(data):
    """Reads back a format-0 file into (time_ms, status, pitch) triples."""
    assert data[:4] == b"MThd"
    _, fmt, tracks, division = struct.unpack(">IHHH", data[4:14])
    assert (fmt, tracks) == (0, 1)
    assert data[14:18] == b"MTrk"
    length = struct.unpack(">I", data[18:22])[0]
    track = data[22 : 22 + length]

    events = []
    index = 0
    now = 0
    while index < len(track):
        delta, index = read_variable_length(track, index)
        now += delta
        status = track[index]
        if status == 0xFF:
            meta = track[index + 1]
            size, index = read_variable_length(track, index + 2)
            index += size
            if meta == 0x2F:
                events.append((now, "end", None))
        elif status in (0x80, 0x90):
            events.append((now, "off" if status == 0x80 else "on", track[index + 1]))
            index += 3
        elif status == 0xC0:
            index += 2
        else:  # pragma: no cover - the writer emits nothing else
            raise AssertionError(f"unexpected status byte {status:#x}")
    return division, events


class MidiTest(unittest.TestCase):
    def sheet(self, takes, **arguments):
        sheets, _ = capture.plan(takes, **arguments)
        return sheets[0]

    def test_one_tick_is_one_millisecond_so_capture_timing_survives(self):
        division, events = parse_midi(render_takes.midi_bytes(self.sheet([take("a", [60])])))
        self.assertEqual(render_takes.MIDI_TICKS_PER_QUARTER, division)
        onset = next(time for time, kind, _ in events if kind == "on")
        self.assertEqual(capture.DEFAULT_LEAD_IN_MS, onset)

    def test_every_note_is_written_at_the_pitch_it_was_played_at(self):
        _, events = parse_midi(render_takes.midi_bytes(self.sheet([take("a", [48, 55, 64])])))
        self.assertEqual([48, 55, 64], sorted(pitch for _, kind, pitch in events if kind == "on"))
        self.assertEqual([48, 55, 64], sorted(pitch for _, kind, pitch in events if kind == "off"))

    def test_a_repeated_pitch_retriggers_because_the_release_is_written_first(self):
        # Two takes of the same note back to back. If the note-on sorted before the note-off at a
        # shared tick, the second strike would be silenced by the first note's release.
        sheet = self.sheet([take("a", [60], off_ms=2500), take("b", [60])], slot_ms=2500)
        _, events = parse_midi(render_takes.midi_bytes(sheet))
        at_boundary = [kind for time, kind, _ in events if time == 4000]
        self.assertEqual(["off", "on"], at_boundary)

    def test_the_file_runs_to_the_end_of_the_sheet_not_to_the_last_release(self):
        sheet = self.sheet([take("a", [60], off_ms=800)], slot_ms=2500, lead_in_ms=1500)
        _, events = parse_midi(render_takes.midi_bytes(sheet))
        self.assertEqual(sheet.duration_ms, next(t for t, kind, _ in events if kind == "end"))


@unittest.skipIf(numpy is None, "the built-in synthesizer needs numpy")
class SynthesizerTest(unittest.TestCase):
    sample_rate = 22_050

    def sheet(self, takes, **arguments):
        sheets, _ = capture.plan(takes, **arguments)
        return sheets[0]

    def loudest_pitches(self, samples, at_ms, count):
        """The strongest pitch classes in a 300 ms window, as MIDI note numbers."""
        start = int(at_ms * self.sample_rate / 1000)
        window = samples[start : start + int(0.3 * self.sample_rate)]
        spectrum = numpy.abs(numpy.fft.rfft(window * numpy.hanning(len(window))))
        frequencies = numpy.fft.rfftfreq(len(window), 1 / self.sample_rate)
        # Fold energy onto MIDI numbers so a harmonic of one note and the fundamental of another
        # are compared on the same axis, then take the strongest.
        strength: dict[int, float] = {}
        for frequency, magnitude in zip(frequencies[1:], spectrum[1:]):
            note = int(round(69 + 12 * numpy.log2(frequency / 440.0)))
            strength[note] = strength.get(note, 0.0) + float(magnitude)
        return sorted(sorted(strength, key=strength.get, reverse=True)[:count])

    def test_the_audio_contains_the_notes_that_were_played(self):
        sheet = self.sheet([take("a", [48, 52, 55], off_ms=2000)], lead_in_ms=1000)
        samples = render_takes.synthesize(sheet, self.sample_rate)
        self.assertEqual([48, 52, 55], self.loudest_pitches(samples, at_ms=1100, count=3))

    def test_a_chord_sounds_in_its_own_slot_and_not_in_the_one_before_it(self):
        sheet = self.sheet(
            [take("a", [60], off_ms=1000), take("b", [67], off_ms=1000)],
            slot_ms=2500,
            lead_in_ms=1000,
        )
        samples = render_takes.synthesize(sheet, self.sample_rate)
        self.assertEqual([60], self.loudest_pitches(samples, at_ms=1100, count=1))
        self.assertEqual([67], self.loudest_pitches(samples, at_ms=3600, count=1))

    def test_the_mix_is_normalized_below_full_scale_so_nothing_clips(self):
        sheet = self.sheet([take("a", list(range(48, 60)), off_ms=2000)])
        peak = float(numpy.max(numpy.abs(render_takes.synthesize(sheet, self.sample_rate))))
        self.assertAlmostEqual(render_takes.SYNTH_PEAK, peak, places=6)

    def test_the_render_runs_past_the_last_chord_so_its_decay_is_inside_the_file(self):
        sheet = self.sheet([take("a", [60])], slot_ms=2500, lead_in_ms=1500)
        samples = render_takes.synthesize(sheet, self.sample_rate)
        self.assertGreater(len(samples) / self.sample_rate, sheet.duration_ms / 1000)


@unittest.skipIf(numpy is None, "the built-in synthesizer needs numpy")
class RenderTest(unittest.TestCase):
    """The whole render step, from a takes file on disk to audio and truth beside each other."""

    def takes_file(self, directory):
        import json

        rows = []
        for index, (letter, quality, seventh, pitches) in enumerate(
            [
                ("C", "MAJOR", "MAJOR", [48, 52, 55, 59]),
                ("D", "MINOR", "MINOR", [50, 53, 57, 60]),
                ("G", "POWER", "NONE", [55, 62]),
            ]
        ):
            rows.append(
                json.dumps(
                    {
                        "id": f"take-{index}",
                        "block": "CORE",
                        "chord": chord_json(letter, quality, seventh),
                        "voicing": "CLOSE",
                        "inversion": 0,
                        "extraIntervals": [],
                        "notes": [
                            {"pitch": pitch, "velocity": 96, "onMs": 500, "offMs": 2000}
                            for pitch in pitches
                        ],
                    }
                )
            )
        path = Path(directory) / "takes.jsonl"
        path.write_text("\n".join(rows) + "\n", encoding="utf-8")
        return path

    def test_it_writes_audio_truth_and_a_manifest_that_says_what_made_the_audio(self):
        import json

        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "out"
            sheets = render_takes.render(
                [self.takes_file(directory)], out, backend="synth", sample_rate=22_050,
                quiet=True,
            )
            self.assertEqual(1, len(sheets))
            self.assertEqual(2, sheets[0].chord_count)  # the power chord is excluded

            with wave.open(str(sheets[0].audio_path), "rb") as handle:
                self.assertEqual(1, handle.getnchannels())
                self.assertEqual(22_050, handle.getframerate())
                self.assertGreater(handle.getnframes() / 22_050, sheets[0].duration_ms / 1000)

            truth = sheets[0].truth_path.read_text(encoding="utf-8")
            self.assertIn("C:maj7", truth)
            self.assertIn("D:min7", truth)

            manifest = json.loads((out / "render-manifest.json").read_text(encoding="utf-8"))
            self.assertEqual("synth", manifest["backend"])
            self.assertEqual(3, manifest["takesRead"])
            self.assertEqual(2, manifest["takesRendered"])
            self.assertEqual(["take-2"], manifest["excluded"]["unspellable"])

    def test_auto_takes_the_soundfont_when_there_is_one_and_falls_back_when_there_is_not(self):
        import unittest.mock as mock

        with mock.patch.object(render_takes.shutil, "which", return_value="/usr/bin/fluidsynth"), \
             mock.patch.object(render_takes, "find_soundfont", return_value=Path("gm.sf2")):
            self.assertEqual("fluidsynth", render_takes.resolve_backend("auto", None))
        with mock.patch.object(render_takes.shutil, "which", return_value=None):
            self.assertEqual("synth", render_takes.resolve_backend("auto", None))

    def test_auto_falls_back_when_fluidsynth_is_installed_but_has_no_soundfont(self):
        import unittest.mock as mock

        with mock.patch.object(render_takes.shutil, "which", return_value="/usr/bin/fluidsynth"), \
             mock.patch.object(render_takes, "find_soundfont", side_effect=SystemExit("none")):
            self.assertEqual("synth", render_takes.resolve_backend("auto", None))

    def test_an_explicit_backend_is_never_second_guessed(self):
        self.assertEqual("synth", render_takes.resolve_backend("synth", None))
        self.assertEqual("fluidsynth", render_takes.resolve_backend("fluidsynth", None))

    def test_a_corpus_that_lays_out_to_nothing_is_refused_rather_than_rendered_empty(self):
        import json

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "takes.jsonl"
            path.write_text(
                json.dumps(
                    {
                        "id": "power", "block": "CORE", "chord": chord_json(quality="POWER"),
                        "voicing": "CLOSE", "inversion": 0, "extraIntervals": [],
                        "notes": [{"pitch": 55, "velocity": 90, "onMs": 0, "offMs": 900}],
                    }
                )
                + "\n",
                encoding="utf-8",
            )
            with self.assertRaises(SystemExit) as raised:
                render_takes.render([path], Path(directory) / "out", quiet=True)
            self.assertIn("power chords", str(raised.exception))

    def test_the_chart_and_the_truth_it_writes_score_against_each_other(self):
        # A chart that agrees with the truth exactly must score 100% at every tier. If the
        # renderer's timeline and the evaluator's reading of it disagreed by so much as the unit
        # of time, this would not be 1.0 and every real score would be quietly wrong.
        import evaluate_chart

        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "out"
            sheets = render_takes.render(
                [self.takes_file(directory)], out, backend="synth", sample_rate=22_050,
                quiet=True,
            )
            truth, readable, unreadable = evaluate_chart.read_truth(sheets[0].truth_path)
            self.assertEqual((2, 0), (readable, unreadable))

            tally = evaluate_chart.Tally()
            evaluate_chart.score_pair(truth, list(truth), tally)
            self.assertGreater(tally.chord_ms, 0)
            self.assertEqual(1.0, tally.rate(tally.exact_ms))


if __name__ == "__main__":
    unittest.main()
