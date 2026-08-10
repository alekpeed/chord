#!/usr/bin/env python3
"""Make labeled training audio from chord charts, so the corpus is not limited to music you own.

    python3 generate_corpus.py --out data/generated --keys 12 --stems
    python3 prepare_data.py --annotations data/generated/annotations \\
        --audio data/generated/audio --out data/features

The annotated corpora cover songs you mostly do not have, and the classes they do cover are the
common ones — a model trained on them guesses at half-diminished and augmented chords because it
has barely heard any. Here the chords are written first and the audio is produced from them, so
rare qualities can be asked for deliberately and the label is exact by construction.

Needs `mma` and `fluidsynth`, both packaged:

    sudo apt install mma fluidsynth fluid-soundfont-gm

What this does not give you is a finished record. A SoundFont is recorded instruments, but dry,
unmixed and with nobody singing over it, and MMA's styles are its own combo idiom. Treat it as
coverage of chords real corpora are thin on, to be mixed with real recordings — not as a
replacement for them.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hearsay_training import generate  # noqa: E402
from hearsay_training.generate import Chart, GenerationError, degrees_to_chart  # noqa: E402

DEFAULT_SOUNDFONT = Path("/usr/share/sounds/sf2/FluidR3_GM.sf2")

#: Progressions as scale degrees, so each is written once and rendered in any key.
#:
#: Chosen for vocabulary coverage rather than for being a tune. Every one of the thirteen
#: qualities the model can predict appears here, and the ones the published corpora are thinnest
#: on — dim, aug, sus2, min6, hdim7 — appear in a context that would actually be played that way,
#: because a chord in a progression teaches the recurrent layer something a chord alone does not.
PROGRESSIONS: tuple[tuple[str, str, float, tuple], ...] = (
    (
        "two-five-one-major", "Swing", 132,
        (((2, "min7"),), ((7, "dom7"),), ((0, "maj7"),), ((0, "maj7"),)),
    ),
    (
        "two-five-one-minor", "Swing", 120,
        (((2, "hdim7"),), ((7, "dom7"),), ((0, "min"),), ((0, "min"),)),
    ),
    (
        "diminished-passing", "Ballad", 88,
        (((0, "maj"),), ((1, "dim7"),), ((2, "min7"),), ((7, "dom7"),)),
    ),
    (
        "leading-tone-triad", "Ballad", 96,
        (((0, "maj7"),), ((11, "dim"),), ((9, "min7"),), ((2, "dom7"),)),
    ),
    (
        "augmented-lift", "Swing", 104,
        (((0, "maj"),), ((0, "aug"),), ((0, "maj6"),), ((0, "maj"),)),
    ),
    (
        "minor-six-vamp", "Swing", 116,
        (((0, "min"),), ((0, "min6"),), ((0, "min"),), ((0, "min6"),)),
    ),
    (
        "suspended-resolution", "Ballad", 92,
        (((0, "sus4"),), ((0, "maj"),), ((0, "sus2"),), ((0, "maj"),)),
    ),
    (
        "twelve-bar-blues", "Blues", 108,
        (
            ((0, "dom7"),), ((5, "dom7"),), ((0, "dom7"),), ((0, "dom7"),),
            ((5, "dom7"),), ((5, "dom7"),), ((0, "dom7"),), ((0, "dom7"),),
            ((7, "dom7"),), ((5, "dom7"),), ((0, "dom7"),), ((7, "dom7"),),
        ),
    ),
    (
        # Two chords to the bar, which is the harmonic rhythm most real songs actually have.
        #
        # 'Blues' rather than a swing groove for a measured reason: asked for two chords in a bar,
        # most grooves comp the second one a whole beat before the bar says it starts. That is
        # idiomatic playing and not a fault in MMA, but it puts the audio ahead of the label, and
        # a label that is early by a beat teaches the model to hear changes early. Of the grooves
        # tried, this one places the change where the chart puts it.
        "half-bar-turnaround", "Blues", 128,
        (
            ((2, "min7"), (7, "dom7")),
            ((0, "maj7"), (9, "dom7")),
            ((2, "min7"), (7, "dom7")),
            ((0, "maj7"),),
        ),
    ),
)


def charts(keys: int, repeats: int) -> list[Chart]:
    """One chart per progression per key, each repeated to a usable length.

    Repeating is what makes a four-bar progression long enough to train on, and it is also the
    thing to be careful about: a repeated loop is far less data than its duration suggests, since
    the model sees the same four bars over and over. Keep this low and add progressions instead.
    """
    built: list[Chart] = []
    for name, groove, tempo, degrees in PROGRESSIONS:
        for key in range(min(max(keys, 1), 12)):
            chart = degrees_to_chart(
                name=f"{name}-key-{generate.ROOT_NAMES[key].replace('#', 'sharp').lower()}",
                tempo=tempo,
                groove=groove,
                degrees=degrees * repeats,
                key=key,
            )
            built.append(chart)
    return built


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--soundfont", type=Path, default=DEFAULT_SOUNDFONT)
    parser.add_argument("--keys", type=int, default=12, help="how many of the 12 keys to render")
    parser.add_argument("--repeats", type=int, default=4, help="times each progression repeats")
    parser.add_argument("--stems", action="store_true", help="also write per-instrument audio")
    parser.add_argument("--limit", type=int, default=0, help="stop after N tracks, for a trial")
    parser.add_argument(
        "--allow-extra-tones",
        action="store_true",
        help="keep a track whose comping adds tones outside the chord, rather than rejecting it",
    )
    args = parser.parse_args()

    missing = generate.missing_tools(args.soundfont)
    if missing:
        print("Cannot generate audio. Missing: " + ", ".join(missing))
        print("  sudo apt install mma fluidsynth fluid-soundfont-gm")
        print("  pip install mido")
        return 1

    audio_dir = args.out / "audio"
    annotation_dir = args.out / "annotations"
    work_dir = args.out / "work"
    for directory in (audio_dir, annotation_dir, work_dir):
        directory.mkdir(parents=True, exist_ok=True)

    written = 0
    rejected: list[str] = []

    for chart in charts(keys=args.keys, repeats=args.repeats):
        try:
            midi = generate.write_midi(chart, work_dir)

            problems = generate.timing_problems(midi, chart)
            voiced = generate.comping_notes(midi, chart)
            for problem in chart.problems_against(voiced):
                if args.allow_extra_tones and "voiced with" in problem:
                    continue
                problems.append(problem)

            if problems:
                # Refused rather than written with a warning. A pair whose audio does not contain
                # the chord its label names is worse than no pair at all: nothing downstream can
                # tell it is wrong, and it drags the model toward a chord that was never played.
                rejected.append(f"{chart.name}: " + "; ".join(problems))
                continue

            generate.render(midi, audio_dir / f"{chart.name}.wav", args.soundfont)
            (annotation_dir / f"{chart.name}.lab").write_text(chart.lab_text(), encoding="utf-8")

            if args.stems:
                stem_dir = args.out / "stems" / chart.name
                for stem_midi in generate.split_stems(midi, work_dir / chart.name):
                    generate.render(stem_midi, stem_dir / f"{stem_midi.stem}.wav", args.soundfont)

            written += 1
            print(f"  ✓ {chart.name}  ({chart.duration:.1f}s, {len(chart.spans())} chords)")
        except GenerationError as error:
            rejected.append(f"{chart.name}: {error}")

        if args.limit and written >= args.limit:
            break

    print()
    print(f"Wrote {written} tracks to {audio_dir} with labels in {annotation_dir}")
    if rejected:
        report = args.out / "rejected.txt"
        report.write_text("\n".join(rejected), encoding="utf-8")
        print(f"{len(rejected)} were rejected because the audio did not match the label.")
        print(f"The reasons are in {report} — each one is a chart or groove to fix, not noise.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
