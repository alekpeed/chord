#!/usr/bin/env python3
"""The measurement loop: capture corpus in, analyzer accuracy out.

    python3 measure_capture.py --takes ~/hearsay-capture/takes.jsonl --out data/measure

Three steps, run in order, with nothing between them a person has to remember:

1. render the captured MIDI to audio and write the Harte truth beside it (:mod:`render_takes`),
2. run the desktop analyzer over that audio,
3. score its charts against the truth (:mod:`evaluate_chart`).

Until this existed, the analyzer's accuracy had never been measured on any material at all, and
every claim about it — including the ones in this repository's own documents — was a story about
proxy metrics. What comes out of here is a number.

Read the number with its provenance, which is why ``measurement.json`` carries the render backend
next to the scores and why the summary prints it. A score against the built-in tone generator and
a score against a soundfont piano are not the same claim, and neither is a claim about records.

The analyzer has to be built first::

    ./gradlew :tools:analyzer:installDist
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import render_takes  # noqa: E402
from hearsay_training import capture  # noqa: E402

REPOSITORY_ROOT = Path(__file__).resolve().parent.parent

DEFAULT_ANALYZER = REPOSITORY_ROOT / "tools/analyzer/build/install/analyzer/bin/analyzer"


def find_analyzer(explicit: Path | None) -> Path:
    candidate = explicit or DEFAULT_ANALYZER
    if candidate.exists():
        return candidate
    raise SystemExit(
        f"measure_capture: no analyzer at {candidate}\n"
        "Build it first:\n"
        "  ./gradlew :tools:analyzer:installDist\n"
        "or point at one with --analyzer."
    )


def run_analyzer(
    analyzer: Path, sheets: list[render_takes.RenderedSheet], charts_directory: Path, profile: str
) -> list[Path]:
    """Analyzes every rendered sheet, returning the chart written for each.

    One invocation per sheet rather than one over the folder: a sheet that fails should be named
    and counted, not lost inside a batch summary, and the charts have to line up with the truth
    files pairwise afterward.
    """
    if shutil.which("ffmpeg") is None:
        raise SystemExit(
            "measure_capture: the analyzer decodes through ffmpeg and it is not on the path.\n"
            "  sudo apt install ffmpeg"
        )
    charts_directory.mkdir(parents=True, exist_ok=True)

    charts: list[Path] = []
    for position, sheet in enumerate(sheets, start=1):
        print(f"  [{position}/{len(sheets)}] {sheet.name} … ", end="", flush=True)
        result = subprocess.run(
            [
                str(analyzer), str(sheet.audio_path),
                "--out", str(charts_directory),
                "--profile", profile,
                "--force",
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        chart = charts_directory / f"{sheet.audio_path.stem}.hearsay.json"
        if result.returncode != 0 or not chart.exists():
            raise SystemExit(
                f"\nmeasure_capture: the analyzer failed on {sheet.audio_path.name}\n"
                f"{result.stdout.strip()}\n{result.stderr.strip()}"
            )
        found = len(json.loads(chart.read_text(encoding="utf-8")).get("chords", []))
        print(f"{found} chords")
        charts.append(chart)
    return charts


def score(
    charts: list[Path], sheets: list[render_takes.RenderedSheet], out_directory: Path, tolerance_ms: int
) -> dict:
    """Runs the evaluator over every chart/truth pair and returns what it wrote."""
    scores_path = out_directory / "scores.json"
    command = [sys.executable, str(Path(__file__).resolve().parent / "evaluate_chart.py")]
    for chart, sheet in zip(charts, sheets):
        command += ["--chart", str(chart), "--truth", str(sheet.truth_path)]
    command += ["--tolerance-ms", str(tolerance_ms), "--json", str(scores_path)]

    result = subprocess.run(command, capture_output=True, text=True, check=False)
    print(result.stdout.rstrip())
    if result.stderr.strip():
        print(result.stderr.rstrip(), file=sys.stderr)
    if result.returncode != 0:
        raise SystemExit("measure_capture: the evaluator refused to score. Nothing is claimed.")
    return json.loads(scores_path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--takes", type=Path, action="append", required=True,
                        help="takes.jsonl from a capture session; repeatable")
    parser.add_argument("--out", type=Path, required=True, help="working directory for this run")
    parser.add_argument("--analyzer", type=Path, help="the analyzer CLI (default: the built one)")
    parser.add_argument("--profile", default="balanced", choices=["fast", "balanced", "maximum"])
    parser.add_argument("--backend", choices=["auto", "synth", "fluidsynth"], default="auto",
                        help="auto uses a soundfont when one is installed (default)")
    parser.add_argument("--soundfont", type=Path)
    parser.add_argument("--program", type=int, default=0, help="General MIDI program (0 = grand piano)")
    parser.add_argument("--slot-ms", type=int, default=capture.DEFAULT_SLOT_MS)
    parser.add_argument("--takes-per-sheet", type=int, default=capture.DEFAULT_TAKES_PER_SHEET)
    parser.add_argument("--label-gaps", action="store_true",
                        help="also score whether the analyzer keeps quiet between chords")
    parser.add_argument("--tolerance-ms", type=int, default=150)
    parser.add_argument("--label", default="", help="a name for this run, written into the summary")
    args = parser.parse_args()

    analyzer = find_analyzer(args.analyzer)
    audio_directory = args.out / "audio"
    charts_directory = args.out / "charts"

    print("Rendering")
    sheets = render_takes.render(
        args.takes,
        audio_directory,
        backend=args.backend,
        soundfont=args.soundfont,
        slot_ms=args.slot_ms,
        takes_per_sheet=args.takes_per_sheet,
        label_gaps=args.label_gaps,
        program=args.program,
    )

    print(f"\nAnalyzing ({args.profile} profile)")
    charts = run_analyzer(analyzer, sheets, charts_directory, args.profile)

    print("\nScoring")
    scores = score(charts, sheets, args.out, args.tolerance_ms)

    manifest = json.loads((audio_directory / "render-manifest.json").read_text(encoding="utf-8"))
    summary = {
        "label": args.label,
        "analyzerProfile": args.profile,
        "analyzer": str(analyzer),
        "render": manifest,
        "scores": scores,
    }
    (args.out / "measurement.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    source = (
        f"{manifest['backend']} backend"
        if manifest["backend"] != "fluidsynth"
        else f"soundfont {Path(manifest['soundfont']).name}"
    )
    print(
        f"\nWritten to {args.out / 'measurement.json'}\n"
        f"This measures the {args.profile} profile on {manifest['takesRendered']} isolated "
        f"chords rendered through the {source}.\n"
        "It is not a measurement of the app on records: there are no drums, no bass guitar, no "
        "vocals and no mix here, and one chord at a time exercises identification without "
        "exercising the decoder."
    )
    if manifest["backend"] == "synth":
        print(
            "The built-in synthesizer is a tone generator. Re-run with --backend fluidsynth "
            "before treating any of this as a piano result."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
