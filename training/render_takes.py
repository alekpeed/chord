#!/usr/bin/env python3
"""Renders a capture corpus into audio the analyzer can be pointed at, with truth files beside it.

    python3 render_takes.py --takes ~/hearsay-capture/takes.jsonl --out data/capture-audio

Each take in ``takes.jsonl`` is one verified chord — a second or two of playing whose label is
correct by construction. This lays them end to end on a fixed grid into a few minute-long
*sheets*, writes a MIDI file and a WAV for each, and writes the matching Harte ``.lab`` that
:mod:`evaluate_chart` scores against. What comes out is the first material this project has ever
had where the right answer is known.

Two backends make the audio, and ``--backend auto`` (the default) takes the better one this
machine can run:

``fluidsynth`` plays the MIDI through a soundfont. This is the one to trust. A General MIDI piano
has the overtone structure, the attack noise and the decay of a real instrument, which is the
thing the front end has to survive::

    sudo apt install fluidsynth fluid-soundfont-gm

``synth`` is the additive synthesizer in this file — a harmonic series per note with per-partial
decay — and is used only when no soundfont is installed. It has real overtones, so it is far from
a trivially easy case; in fact it is the *harder* one, and by a wide margin. On the same corpus
through the same analyzer it scored 3% root accuracy where the soundfont piano scored 48%. **A
score from this backend measures the analyzer against a tone generator, not against a piano**,
and must be reported saying so — which is why the resolved backend is written into
``render-manifest.json`` beside the audio and into every summary printed here.

Either way the known limit stands and is worth restating: synthesized solo piano has no drums,
no bass guitar, no vocals, no mix compression, and isolated chords exercise chord identification
without exercising the decoder. A good score here does not mean the app works on records. A bad
one localizes the fault immediately.
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import struct
import subprocess
import sys
import wave
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hearsay_training import capture  # noqa: E402

#: Rendered at CD rate and left there. The analyzer resamples to its own analysis rate through
#: ffmpeg, and handing it audio already at that rate would skip a resampling step the app really
#: performs — measuring a pipeline one stage shorter than the one that ships.
DEFAULT_SAMPLE_RATE = 44_100

#: Ticks per quarter note in the written MIDI, paired with a one-second quarter note below so
#: that one tick is exactly one millisecond and no capture timing is rounded on the way out.
MIDI_TICKS_PER_QUARTER = 1_000
MIDI_MICROSECONDS_PER_QUARTER = 1_000_000

#: Where a General MIDI soundfont usually lands on Debian, Ubuntu and Fedora.
SOUNDFONT_CANDIDATES = [
    "/usr/share/sounds/sf2/FluidR3_GM.sf2",
    "/usr/share/sounds/sf2/default-GM.sf2",
    "/usr/share/soundfonts/FluidR3_GM.sf2",
    "/usr/share/soundfonts/default.sf2",
]

#: Partials per note in the built-in synthesizer. Twelve reaches the sixth octave above the
#: fundamental, which is where the phantom fifths and thirds that confuse a chromagram come from;
#: stopping at three or four would render a problem easier than the real one.
SYNTH_PARTIALS = 12

#: How fast partial amplitude falls with harmonic number, and how much faster the high ones decay.
#: Neither is a measurement of any instrument — they are the shape of a plucked or struck string,
#: chosen so the result is recognizably pitched rather than to imitate a specific piano.
SYNTH_PARTIAL_ROLLOFF = 1.4
SYNTH_PARTIAL_DAMPING = 0.35

SYNTH_ATTACK_MS = 6.0
SYNTH_RELEASE_MS = 90.0

#: Peak the mixed sheet is normalized to, leaving headroom so nothing clips on the way to 16 bit.
SYNTH_PEAK = 0.89


@dataclass
class RenderedSheet:
    name: str
    audio_path: Path
    midi_path: Path
    truth_path: Path
    duration_ms: int
    chord_count: int


# --------------------------------------------------------------------------------------------
# MIDI
# --------------------------------------------------------------------------------------------


def _variable_length(value: int) -> bytes:
    """MIDI's seven-bits-per-byte integer encoding, high byte first, continuation bit set."""
    buffer = [value & 0x7F]
    value >>= 7
    while value:
        buffer.append((value & 0x7F) | 0x80)
        value >>= 7
    return bytes(reversed(buffer))


def midi_bytes(sheet: capture.Sheet, *, program: int = 0) -> bytes:
    """One sheet as a format-0 MIDI file, one tick per millisecond.

    Program 0 is the General MIDI acoustic grand. It is a parameter because the corpus is worth
    rendering through more than one instrument — the same take through a Rhodes and through a
    guitar is two different tests of the same recognizer, at no extra playing.
    """
    events: list[tuple[int, int, bytes]] = []
    for take in sheet.takes:
        for note in take.notes:
            # Note-offs sort before note-ons at the same instant, so a repeated pitch retriggers
            # instead of the second onset being cut short by the first note's release.
            events.append((note.end_ms, 0, bytes([0x80, note.pitch, 0])))
            events.append((note.start_ms, 1, bytes([0x90, note.pitch, max(1, min(127, note.velocity))])))
    events.sort(key=lambda event: (event[0], event[1]))

    track = bytearray()
    track += _variable_length(0) + b"\xff\x51\x03" + MIDI_MICROSECONDS_PER_QUARTER.to_bytes(3, "big")
    track += _variable_length(0) + bytes([0xC0, program & 0x7F])

    previous_ms = 0
    for time_ms, _, message in events:
        track += _variable_length(max(0, time_ms - previous_ms)) + message
        previous_ms = time_ms
    tail = max(0, sheet.duration_ms - previous_ms)
    track += _variable_length(tail) + b"\xff\x2f\x00"

    header = b"MThd" + struct.pack(">IHHH", 6, 0, 1, MIDI_TICKS_PER_QUARTER)
    return header + b"MTrk" + struct.pack(">I", len(track)) + bytes(track)


# --------------------------------------------------------------------------------------------
# The built-in synthesizer
# --------------------------------------------------------------------------------------------


def synthesize(sheet: capture.Sheet, sample_rate: int):
    """Mixes one sheet to a mono float array with the additive synthesizer described above."""
    try:
        import numpy
    except ImportError as error:  # pragma: no cover - environment, not logic
        raise SystemExit(
            "render_takes: the built-in synthesizer needs numpy.\n"
            "  pip install -r requirements.txt\n"
            "Or render through a soundfont instead, which needs no Python packages:\n"
            "  --backend fluidsynth"
        ) from error

    tail_ms = SYNTH_RELEASE_MS + 500
    total = int((sheet.duration_ms + tail_ms) * sample_rate / 1000) + 1
    mix = numpy.zeros(total, dtype=numpy.float64)

    for take in sheet.takes:
        for note in take.notes:
            frequency = 440.0 * (2.0 ** ((note.pitch - 69) / 12.0))
            start = int(note.start_ms * sample_rate / 1000)
            held = max(1, int((note.end_ms - note.start_ms) * sample_rate / 1000))
            release = int(SYNTH_RELEASE_MS * sample_rate / 1000)
            length = min(held + release, total - start)
            if length <= 0:
                continue

            time = numpy.arange(length, dtype=numpy.float64) / sample_rate
            voice = numpy.zeros(length, dtype=numpy.float64)
            # Lower notes ring longer, the way a longer heavier string does.
            fundamental_decay = 2.4 * (2.0 ** ((69 - note.pitch) / 24.0))
            for partial in range(1, SYNTH_PARTIALS + 1):
                partial_frequency = frequency * partial
                if partial_frequency >= sample_rate / 2:
                    break
                amplitude = partial ** -SYNTH_PARTIAL_ROLLOFF
                decay = fundamental_decay / (1.0 + SYNTH_PARTIAL_DAMPING * (partial - 1))
                # A phase offset per partial, deterministic in the partial number: starting every
                # harmonic at zero stacks their peaks into a click that is louder than the note.
                phase = (partial * 0.618) * 2.0 * math.pi
                voice += amplitude * numpy.exp(-time / decay) * numpy.sin(
                    2.0 * math.pi * partial_frequency * time + phase
                )

            envelope = numpy.ones(length, dtype=numpy.float64)
            attack = min(length, max(1, int(SYNTH_ATTACK_MS * sample_rate / 1000)))
            envelope[:attack] = numpy.linspace(0.0, 1.0, attack)
            if held < length:
                fade = length - held
                envelope[held:] *= numpy.linspace(1.0, 0.0, fade) ** 2
            voice *= envelope * ((note.velocity / 127.0) ** 1.6)
            mix[start : start + length] += voice

    peak = float(numpy.max(numpy.abs(mix))) if mix.size else 0.0
    if peak > 0:
        mix *= SYNTH_PEAK / peak
    return mix


def write_wav(path: Path, samples, sample_rate: int) -> None:
    import numpy

    clipped = numpy.clip(samples, -1.0, 1.0)
    pcm = (clipped * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(pcm.tobytes())


# --------------------------------------------------------------------------------------------
# The soundfont backend
# --------------------------------------------------------------------------------------------


def resolve_backend(requested: str, soundfont: Path | None) -> str:
    """Turns ``auto`` into whichever backend this machine can actually run.

    The soundfont path is the one to be on, so it is taken whenever it is available. Defaulting
    the other way round — which this did at first — quietly hands most runs to the tone generator,
    and the tone generator is measurably the harder case: on the same corpus and the same
    analyzer, it scored 3% root against the soundfont piano's 48%. A default that produces a
    number four-fifths worse than the one the user would get by installing a package is a default
    that misleads.
    """
    if requested != "auto":
        return requested
    if shutil.which("fluidsynth") is None:
        return "synth"
    try:
        find_soundfont(soundfont)
    except SystemExit:
        return "synth"
    return "fluidsynth"


def find_soundfont(explicit: Path | None) -> Path:
    if explicit is not None:
        if not explicit.exists():
            raise SystemExit(f"render_takes: no soundfont at {explicit}")
        return explicit
    for candidate in SOUNDFONT_CANDIDATES:
        if Path(candidate).exists():
            return Path(candidate)
    raise SystemExit(
        "render_takes: --backend fluidsynth needs a soundfont and none was found.\n"
        "  sudo apt install fluidsynth fluid-soundfont-gm\n"
        "Then it lands at /usr/share/sounds/sf2/FluidR3_GM.sf2, or pass --soundfont."
    )


def render_with_fluidsynth(midi_path: Path, audio_path: Path, soundfont: Path, sample_rate: int) -> None:
    if shutil.which("fluidsynth") is None:
        raise SystemExit(
            "render_takes: fluidsynth is not on the path.\n"
            "  sudo apt install fluidsynth fluid-soundfont-gm"
        )
    result = subprocess.run(
        [
            "fluidsynth", "-ni", "-g", "0.8",
            "-r", str(sample_rate),
            "-F", str(audio_path),
            str(soundfont), str(midi_path),
        ],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0 or not audio_path.exists():
        raise SystemExit(
            f"render_takes: fluidsynth failed on {midi_path.name}\n{result.stderr.strip()}"
        )


# --------------------------------------------------------------------------------------------


def render(
    takes_paths: list[Path],
    out_directory: Path,
    *,
    backend: str = "auto",
    soundfont: Path | None = None,
    sample_rate: int = DEFAULT_SAMPLE_RATE,
    slot_ms: int = capture.DEFAULT_SLOT_MS,
    takes_per_sheet: int = capture.DEFAULT_TAKES_PER_SHEET,
    label_gaps: bool = False,
    program: int = 0,
    quiet: bool = False,
) -> list[RenderedSheet]:
    """Reads every takes file, lays the corpus out, and writes MIDI, audio and truth files."""
    takes: list[capture.Take] = []
    unreadable = 0
    for path in takes_paths:
        if not path.exists():
            raise SystemExit(f"render_takes: no takes file at {path}")
        read, bad = capture.read_takes(path)
        takes += read
        unreadable += bad

    if not takes:
        raise SystemExit(
            "render_takes: the takes file held no readable takes. That is a broken input, "
            "not an empty corpus."
        )
    if unreadable:
        print(f"warning: {unreadable} unreadable line(s) in the takes file(s), skipped", file=sys.stderr)

    sheets, excluded = capture.plan(takes, slot_ms=slot_ms, takes_per_sheet=takes_per_sheet)
    if not sheets:
        raise SystemExit(
            f"render_takes: none of the {len(takes)} takes could be laid out.\n"
            + "\n".join(f"  {line}" for line in excluded.describe())
            + "\nWriting an empty corpus and scoring against it would produce a number for a "
            "comparison that never happened."
        )
    backend = resolve_backend(backend, soundfont)
    resolved_soundfont = find_soundfont(soundfont) if backend == "fluidsynth" else None
    out_directory.mkdir(parents=True, exist_ok=True)

    rendered: list[RenderedSheet] = []
    for sheet in sheets:
        midi_path = out_directory / f"{sheet.name}.mid"
        audio_path = out_directory / f"{sheet.name}.wav"
        truth_path = out_directory / f"{sheet.name}.lab"

        midi_path.write_bytes(midi_bytes(sheet, program=program))
        truth_path.write_text(
            "\n".join(capture.truth_lines(sheet, label_gaps=label_gaps)) + "\n", encoding="utf-8"
        )
        if backend == "fluidsynth":
            assert resolved_soundfont is not None
            render_with_fluidsynth(midi_path, audio_path, resolved_soundfont, sample_rate)
        else:
            write_wav(audio_path, synthesize(sheet, sample_rate), sample_rate)

        rendered.append(
            RenderedSheet(
                name=sheet.name,
                audio_path=audio_path,
                midi_path=midi_path,
                truth_path=truth_path,
                duration_ms=sheet.duration_ms,
                chord_count=len(sheet.takes),
            )
        )
        if not quiet:
            print(f"  {sheet.name}: {len(sheet.takes)} chords, {sheet.duration_ms / 1000:.0f}s")

    manifest = {
        "backend": backend,
        "soundfont": str(resolved_soundfont) if resolved_soundfont else None,
        "midiProgram": program,
        "sampleRate": sample_rate,
        "slotMs": slot_ms,
        "takesPerSheet": takes_per_sheet,
        "labeledGaps": label_gaps,
        "takesRead": len(takes),
        "takesRendered": sum(sheet.chord_count for sheet in rendered),
        "unreadableLines": unreadable,
        "excluded": {
            "unspellable": excluded.unspellable,
            "empty": excluded.empty,
            "neverSoundedTogether": excluded.never_sounded_together,
        },
        "sheets": [
            {
                "name": sheet.name,
                "audio": sheet.audio_path.name,
                "truth": sheet.truth_path.name,
                "chords": sheet.chord_count,
                "durationMs": sheet.duration_ms,
            }
            for sheet in rendered
        ],
    }
    (out_directory / "render-manifest.json").write_text(
        json.dumps(manifest, indent=2), encoding="utf-8"
    )

    if not quiet:
        total = sum(sheet.chord_count for sheet in rendered)
        print(
            f"\n{len(rendered)} sheet(s), {total} of {len(takes)} takes rendered "
            f"({backend} backend)"
        )
        for line in excluded.describe():
            print(f"  {line}")
        if backend == "synth":
            print(
                "  NOTE: the built-in synthesizer is a tone generator, not a piano, and it is the "
                "harder case — on one corpus it scored 3% root where the soundfont piano scored "
                "48%. Install fluidsynth and fluid-soundfont-gm before believing any of this:\n"
                "    sudo apt install fluidsynth fluid-soundfont-gm"
            )
    return rendered


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--takes", type=Path, action="append", required=True,
                        help="takes.jsonl from a capture session; repeatable")
    parser.add_argument("--out", type=Path, required=True, help="where to write audio and .lab files")
    parser.add_argument("--backend", choices=["auto", "synth", "fluidsynth"], default="auto",
                        help="auto uses a soundfont when one is installed (default)")
    parser.add_argument("--soundfont", type=Path, help="soundfont for --backend fluidsynth")
    parser.add_argument("--program", type=int, default=0, help="General MIDI program (0 = grand piano)")
    parser.add_argument("--sample-rate", type=int, default=DEFAULT_SAMPLE_RATE)
    parser.add_argument("--slot-ms", type=int, default=capture.DEFAULT_SLOT_MS)
    parser.add_argument("--takes-per-sheet", type=int, default=capture.DEFAULT_TAKES_PER_SHEET)
    parser.add_argument("--label-gaps", action="store_true",
                        help="also label the silence between chords as no-chord")
    args = parser.parse_args()

    render(
        args.takes,
        args.out,
        backend=args.backend,
        soundfont=args.soundfont,
        sample_rate=args.sample_rate,
        slot_ms=args.slot_ms,
        takes_per_sheet=args.takes_per_sheet,
        label_gaps=args.label_gaps,
        program=args.program,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
