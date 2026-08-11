"""Generating labeled training audio from chord charts you write.

The expensive part of a chord corpus is not the audio, it is the labeling. Isophonics exists
because somebody annotated a few hundred recordings by hand, and that is why the corpus problem
has no cheap answer: you cannot buy more annotated music. Writing the chords first and producing
audio that plays them turns the problem around. The label is then exact by construction rather
than something a person inferred from a recording and might have inferred wrongly.

Two free programs do the work, both packaged on Debian and Ubuntu. ``mma`` is an accompaniment
generator: give it a chord chart and a style and it writes a MIDI arrangement with separate bass,
drum and comping parts. ``fluidsynth`` renders MIDI through a SoundFont, which is recorded
instruments rather than synthesis. Neither is a dependency of the training pipeline; both are
needed only to make a corpus, and their absence is reported rather than crashed on.

**Nothing MMA does is taken on trust.** It is a separate program with its own opinions about what
a chord name means, and measurement found two places where its opinion differs from ours in ways
that would silently corrupt every label:

* ``Cdim`` is voiced as a diminished *seventh*, not a diminished triad. Labeling it ``C:dim``
  describes audio that is not there. The triad is spelled ``Cmb5``.
* A groove that comps on beats one and three will not play a chord written on beat two. MMA
  accepts four chords in a bar and the arrangement quietly plays two of them.
* Asked for two chords in a bar, most grooves *anticipate* — they comp the second chord a whole
  beat before the bar says it begins. That is how the music is really played, and it still puts
  the audio ahead of the label.

Either would produce audio that does not contain the chord the label claims — the one failure
this whole approach exists to avoid, and the same failure `match_audio` was found to have. So
every rendered track is read back and checked against the chart that produced it, and a track
whose audio disagrees with its label is not written at all.

The chart-to-label half of this module is deliberately free of dependencies, because that is
where an off-by-one would train the model on chords that are consistently early or late without
anything ever failing. It is tested with nothing installed.
"""

from __future__ import annotations

import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from . import harte

#: Note names, sharp-spelled to match [harte.class_label] so a generated label reads back exactly.
ROOT_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

#: Our quality -> the MMA chord suffix that is *voiced* as that quality.
#:
#: Verified by reading the notes MMA emits, not by reading its documentation. ``dim`` is the one
#: that bites: MMA voices ``Cdim`` as ``[0, 3, 6, 9]``, a diminished seventh.
MMA_QUALITY = {
    "maj": "",
    "min": "m",
    "dom7": "7",
    "min7": "m7",
    "maj7": "maj7",
    "dim": "mb5",
    "aug": "aug",
    "sus4": "sus4",
    "sus2": "sus2",
    "min6": "m6",
    "maj6": "6",
    "dim7": "dim7",
    "hdim7": "m7b5",
}

#: Semitones above the root that each quality is allowed to sound.
#:
#: Used to check a rendering against its label. A chord voiced with a tone outside its own set is
#: not the chord the label names, whatever the chart asked for.
CHORD_TONES = {
    "maj": frozenset({0, 4, 7}),
    "min": frozenset({0, 3, 7}),
    "dom7": frozenset({0, 4, 7, 10}),
    "min7": frozenset({0, 3, 7, 10}),
    "maj7": frozenset({0, 4, 7, 11}),
    "dim": frozenset({0, 3, 6}),
    "aug": frozenset({0, 4, 8}),
    "sus4": frozenset({0, 5, 7}),
    "sus2": frozenset({0, 2, 7}),
    "min6": frozenset({0, 3, 7, 9}),
    "maj6": frozenset({0, 4, 7, 9}),
    "dim7": frozenset({0, 3, 6, 9}),
    "hdim7": frozenset({0, 3, 6, 10}),
}


#: How far ahead of a chord change a comping part may place the chord, as a fraction of the span.
#:
#: Playing fractionally ahead of the beat is idiomatic rather than an error, so the check has to
#: allow it. Small: a wide value would let a chord be credited to the span before its own.
ANTICIPATION = 0.02


class GenerationError(Exception):
    """A track could not be made, or was made and did not survive checking."""


@dataclass(frozen=True)
class Chord:
    """One chord, in the vocabulary the model is trained on and nothing wider."""

    root: int
    quality: str

    def __post_init__(self) -> None:
        if not 0 <= self.root < 12:
            raise ValueError(f"Root must be a pitch class 0-11, was {self.root}")
        if self.quality not in MMA_QUALITY:
            raise ValueError(f"Not a vocabulary quality: {self.quality}")

    @property
    def mma(self) -> str:
        """How MMA must be asked for this chord for it to voice this chord."""
        return ROOT_NAMES[self.root] + MMA_QUALITY[self.quality]

    @property
    def harte(self) -> str:
        return f"{ROOT_NAMES[self.root]}:{self.quality}"

    @property
    def class_index(self) -> int:
        return harte.QUALITIES.index(self.quality) * 12 + self.root

    @property
    def tones(self) -> frozenset[int]:
        """Absolute pitch classes this chord may sound."""
        return frozenset((self.root + interval) % 12 for interval in CHORD_TONES[self.quality])

    def transposed(self, semitones: int) -> Chord:
        return Chord(root=(self.root + semitones) % 12, quality=self.quality)


@dataclass(frozen=True)
class Span:
    """One chord and the seconds it occupies, which is exactly a line of a `.lab` file."""

    start: float
    end: float
    chord: Chord


@dataclass(frozen=True)
class Chart:
    """A piece of music, written as chords rather than heard as them.

    [bars] holds one tuple per bar, and a bar holds the chords sounding in it, dividing the bar
    evenly. Two chords in a four-beat bar change on beats one and three. More chords than the
    groove actually comps will not be played, which [problems_against] is there to catch.
    """

    name: str
    tempo: float
    groove: str
    bars: tuple[tuple[Chord, ...], ...]
    beats_per_bar: int = 4

    def __post_init__(self) -> None:
        if self.tempo <= 0:
            raise ValueError(f"Tempo must be positive, was {self.tempo}")
        if not self.bars:
            raise ValueError("A chart needs at least one bar")
        for index, bar in enumerate(self.bars, start=1):
            if not bar:
                raise ValueError(f"Bar {index} of {self.name} has no chord")
            if self.beats_per_bar % len(bar):
                raise ValueError(
                    f"Bar {index} of {self.name} has {len(bar)} chords, which does not divide "
                    f"{self.beats_per_bar} beats evenly"
                )

    @property
    def seconds_per_beat(self) -> float:
        return 60.0 / self.tempo

    @property
    def seconds_per_bar(self) -> float:
        return self.seconds_per_beat * self.beats_per_bar

    @property
    def duration(self) -> float:
        return self.seconds_per_bar * len(self.bars)

    def spans(self) -> list[Span]:
        """Every chord with its start and end in seconds.

        Pure arithmetic, and that is the point: the times are not measured from the rendering, so
        they cannot drift from it. The rendering is checked against them instead.
        """
        spans: list[Span] = []
        for index, bar in enumerate(self.bars):
            bar_start = index * self.seconds_per_bar
            share = self.seconds_per_bar / len(bar)
            for position, chord in enumerate(bar):
                start = bar_start + position * share
                spans.append(Span(start=start, end=start + share, chord=chord))
        return spans

    def lab_text(self) -> str:
        """The annotation, in the Harte-notation `.lab` form `read_lab` already consumes."""
        lines = [f"{span.start:.6f} {span.end:.6f} {span.chord.harte}" for span in self.spans()]
        return "\n".join(lines) + "\n"

    def mma_text(self) -> str:
        """The chart, in MMA's own notation.

        No intro, no ending and no repeats: every one of them shifts the music relative to the bar
        numbers, and the bar numbers are what the labels are computed from.
        """
        lines = [
            f"// Generated by hearsay_training.generate for {self.name}.",
            "// Bar numbers here are what the .lab timings are computed from; keep them aligned.",
            f"Tempo {self.tempo:g}",
            f"Groove {self.groove}",
            "",
        ]
        for index, bar in enumerate(self.bars, start=1):
            lines.append(f"{index:<4d} " + " ".join(chord.mma for chord in bar))
        return "\n".join(lines) + "\n"

    def transposed(self, semitones: int) -> Chart:
        return Chart(
            name=f"{self.name}-t{semitones:+d}",
            tempo=self.tempo,
            groove=self.groove,
            bars=tuple(tuple(c.transposed(semitones) for c in bar) for bar in self.bars),
            beats_per_bar=self.beats_per_bar,
        )

    def problems_against(self, voiced: list[tuple[float, int]]) -> list[str]:
        """Checks what was actually played against what this chart claims was played.

        [voiced] is every comping note as ``(seconds, pitch class)``. Two things are looked for,
        and both have been observed rather than imagined: a span with nothing sounding in it,
        which is a chord the groove declined to play, and a span sounding a tone the chord does
        not contain, which is MMA voicing a different chord than the one that was asked for.
        """
        problems: list[str] = []
        for span in self.spans():
            # The window opens slightly before the span and closes slightly before its end, rather
            # than being inset at both ends. A comping part places the chord *on* the change and
            # often a little ahead of it, so a window that started fractionally late would find
            # nothing in any span. Shifting rather than shrinking also keeps every note in exactly
            # one span, so nothing is counted twice or dropped between two of them.
            margin = (span.end - span.start) * ANTICIPATION
            inside = {
                pitch for at, pitch in voiced
                if span.start - margin <= at < span.end - margin
            }
            if not inside:
                problems.append(
                    f"{span.chord.harte} at {span.start:.3f}s is never voiced — the '{self.groove}' "
                    f"groove does not comp there, so the audio does not contain this chord"
                )
                continue
            foreign = inside - span.chord.tones
            if foreign:
                names = ", ".join(ROOT_NAMES[p] for p in sorted(foreign))
                problems.append(
                    f"{span.chord.harte} at {span.start:.3f}s is voiced with {names}, which "
                    f"{span.chord.harte} does not contain"
                )
        return problems


def degrees_to_chart(
    name: str,
    tempo: float,
    groove: str,
    degrees: tuple[tuple[tuple[int, str], ...], ...],
    key: int = 0,
    beats_per_bar: int = 4,
) -> Chart:
    """Builds a chart from scale degrees, so one progression can be written once and keyed later."""
    bars = tuple(
        tuple(Chord(root=(key + offset) % 12, quality=quality) for offset, quality in bar)
        for bar in degrees
    )
    return Chart(name=name, tempo=tempo, groove=groove, bars=bars, beats_per_bar=beats_per_bar)


# --- Everything below needs mma, fluidsynth and mido on the machine. -------------------------


def missing_tools(soundfont: Path | None = None) -> list[str]:
    """Names what is not installed, so the CLI can say so once instead of failing per track."""
    missing = [tool for tool in ("mma", "fluidsynth") if shutil.which(tool) is None]
    try:
        import mido  # noqa: F401
    except ImportError:
        missing.append("python package 'mido'")
    if soundfont is not None and not soundfont.exists():
        missing.append(f"SoundFont {soundfont}")
    return missing


def _run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        tail = (result.stdout + result.stderr).strip().splitlines()
        detail = tail[-1] if tail else "no output"
        raise GenerationError(f"{command[0]} failed: {detail}")


def write_midi(chart: Chart, work: Path) -> Path:
    """Writes the chart and runs MMA over it, returning the MIDI it produced."""
    work.mkdir(parents=True, exist_ok=True)
    source = work / f"{chart.name}.mma"
    source.write_text(chart.mma_text(), encoding="utf-8")
    _run(["mma", str(source)])
    midi = source.with_suffix(".mid")
    if not midi.exists():
        raise GenerationError(f"MMA reported success but wrote no MIDI for {chart.name}")
    return midi


def comping_notes(midi_path: Path, chart: Chart) -> list[tuple[float, int]]:
    """Every comping note as ``(seconds, pitch class)``.

    Only the chord parts. Bass lines walk through passing tones and drums are unpitched, so
    neither says anything about which chord is sounding, and including them would reject
    arrangements that are perfectly correct.
    """
    import mido

    midi = mido.MidiFile(str(midi_path))
    seconds_per_tick = chart.seconds_per_beat / midi.ticks_per_beat
    notes: list[tuple[float, int]] = []
    for track in midi.tracks:
        if not (track.name or "").startswith("Chord"):
            continue
        tick = 0
        for message in track:
            tick += message.time
            if message.type == "note_on" and message.velocity > 0:
                notes.append((tick * seconds_per_tick, message.note % 12))
    return notes


def timing_problems(midi_path: Path, chart: Chart) -> list[str]:
    """Checks the MIDI's own clock against the clock the labels were computed on.

    A count-in is the dangerous one. It shifts every chord in the file by a bar while leaving the
    tempo, the bar count and the file length all looking exactly as expected.
    """
    import mido

    midi = mido.MidiFile(str(midi_path))
    problems: list[str] = []

    tempos = [m.tempo for track in midi.tracks for m in track if m.type == "set_tempo"]
    if not tempos:
        problems.append("no tempo in the MIDI, so the label times cannot be trusted")
    else:
        stated = 60_000_000 / tempos[0]
        if abs(stated - chart.tempo) > 0.01:
            problems.append(f"MIDI says {stated:.3f} BPM, the labels assume {chart.tempo:g}")
        if len(set(tempos)) > 1:
            problems.append("the MIDI changes tempo, which the label arithmetic does not model")

    signatures = [m for track in midi.tracks for m in track if m.type == "time_signature"]
    for signature in signatures[:1]:
        if signature.numerator != chart.beats_per_bar:
            problems.append(
                f"MIDI is in {signature.numerator}/{signature.denominator}, the labels assume "
                f"{chart.beats_per_bar} beats to the bar"
            )

    first = min(
        (tick for track in midi.tracks
         for tick, message in _with_ticks(track)
         if message.type == "note_on" and message.velocity > 0),
        default=None,
    )
    if first is None:
        problems.append("the MIDI contains no notes")
    elif first > 0:
        beats = first / midi.ticks_per_beat
        problems.append(
            f"the first note is {beats:.3f} beats in, not at zero — MMA has added a count-in and "
            f"every label is early by that much"
        )
    return problems


def _with_ticks(track):
    tick = 0
    for message in track:
        tick += message.time
        yield tick, message


def split_stems(midi_path: Path, out_dir: Path) -> list[Path]:
    """Writes one MIDI per instrument part, sharing the original's tempo track.

    Not a separation algorithm and not an approximation of one: the parts were never mixed, so
    the stems are exact and sample-aligned, and they sum back to the mix. That is what makes them
    worth having over anything Demucs can recover from a finished recording.
    """
    import mido

    source = mido.MidiFile(str(midi_path))
    out_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for index, track in enumerate(source.tracks[1:], start=1):
        if not any(m.type == "note_on" for m in track):
            continue
        stem = mido.MidiFile(type=1, ticks_per_beat=source.ticks_per_beat)
        stem.tracks.append(source.tracks[0])
        stem.tracks.append(track)
        name = (track.name or f"part{index}").replace(" ", "_").lower()
        path = out_dir / f"{name}.mid"
        stem.save(str(path))
        written.append(path)
    return written


#: Audio formats worth writing a corpus in. Both are lossless; only the size differs.
#:
#: FLAC decodes to PCM identical to the WAV it came from — verified byte for byte, and the
#: analyzer returns the same chart from either — at roughly a third of the size. A corpus in every
#: key runs to gigabytes as WAV, and none of those bytes buy anything.
AUDIO_FORMATS = ("flac", "wav")


def render(
    midi_path: Path,
    audio_path: Path,
    soundfont: Path,
    sample_rate: int = 44_100,
    audio_format: str = "flac",
) -> None:
    if audio_format not in AUDIO_FORMATS:
        raise ValueError(f"Not a supported format: {audio_format}")
    audio_path.parent.mkdir(parents=True, exist_ok=True)
    _run([
        "fluidsynth", "-ni", "-T", audio_format, "-F", str(audio_path),
        "-r", str(sample_rate), str(soundfont), str(midi_path),
    ])
