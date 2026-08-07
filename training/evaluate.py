#!/usr/bin/env python3
"""The scoreboard.

    python3 evaluate.py --features data/features --checkpoint models/best.pt

Scores a trained model against held-back songs using the measures the product specification
names. Run it before you change anything and after, so "better" is a number rather than an
impression — which is the rule `docs/model-registry.md` sets for accepting any model at all.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hearsay_training import harte  # noqa: E402
from hearsay_training.model import ChordRecogniser  # noqa: E402

ROOT_ONLY = "root"
MAJ_MIN = "majmin"
FULL = "full"


def collapse_to_majmin(class_index: int) -> int:
    """Reduces a class to major or minor, the coarsest standard comparison."""
    if class_index == harte.NO_CHORD_INDEX:
        return class_index
    quality_index, root = divmod(class_index, 12)
    quality = harte.QUALITIES[quality_index]
    minor_like = quality in {"min", "min7", "min6", "dim", "dim7", "hdim7"}
    return (1 if minor_like else 0) * 12 + root


def score(predicted: np.ndarray, actual: np.ndarray) -> dict[str, float]:
    """Weighted chord-symbol recall at three strictnesses.

    Frames annotated as no-chord are excluded throughout: they are not chords, and counting
    them would let a lazy model score well by staying silent.
    """
    mask = actual != harte.NO_CHORD_INDEX
    if not mask.any():
        return {FULL: 0.0, MAJ_MIN: 0.0, ROOT_ONLY: 0.0, "frames": 0.0}

    predicted, actual = predicted[mask], actual[mask]

    full = float((predicted == actual).mean())
    majmin = float(
        (np.array([collapse_to_majmin(int(p)) for p in predicted])
         == np.array([collapse_to_majmin(int(a)) for a in actual])).mean()
    )
    root = float(((predicted % 12) == (actual % 12)).mean())
    return {FULL: full, MAJ_MIN: majmin, ROOT_ONLY: root, "frames": float(len(actual))}


def confusions(predicted: np.ndarray, actual: np.ndarray, top: int = 12):
    """The mistakes it makes most, which is where the next improvement is."""
    counts: dict[tuple[int, int], int] = defaultdict(int)
    mask = actual != harte.NO_CHORD_INDEX
    for p, a in zip(predicted[mask], actual[mask]):
        if p != a:
            counts[(int(a), int(p))] += 1
    ranked = sorted(counts.items(), key=lambda item: item[1], reverse=True)[:top]
    return [
        {"expected": harte.class_label(a), "predicted": harte.class_label(p), "frames": n}
        for (a, p), n in ranked
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--features", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--report", type=Path, default=Path("models/scoreboard.json"))
    parser.add_argument("--validation-fraction", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=1234)
    args = parser.parse_args()

    import random

    files = sorted(args.features.glob("*.npz"))
    if not files:
        raise SystemExit(f"No prepared features in {args.features}")
    random.Random(args.seed).shuffle(files)
    held_back = files[: max(1, int(len(files) * args.validation_fraction))]

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = ChordRecogniser().to(device)
    model.load_state_dict(torch.load(args.checkpoint, map_location=device))
    model.eval()

    all_predicted: list[np.ndarray] = []
    all_actual: list[np.ndarray] = []
    per_song = []

    with torch.no_grad():
        for path in held_back:
            with np.load(path) as archive:
                features, labels = archive["features"], archive["labels"]
            logits = model(torch.from_numpy(features).unsqueeze(0).to(device))
            predicted = logits.argmax(dim=-1).squeeze(0).cpu().numpy()

            all_predicted.append(predicted)
            all_actual.append(labels)
            per_song.append({"song": path.stem, **score(predicted, labels)})

    predicted = np.concatenate(all_predicted)
    actual = np.concatenate(all_actual)
    overall = score(predicted, actual)

    print()
    print(f"Scored on {len(held_back)} songs it has never seen")
    print(f"  Root correct              {overall[ROOT_ONLY] * 100:5.1f}%")
    print(f"  Major/minor correct       {overall[MAJ_MIN] * 100:5.1f}%")
    print(f"  Exact chord correct       {overall[FULL] * 100:5.1f}%")
    print()
    print("Most common mistakes:")
    for row in confusions(predicted, actual):
        print(f"  heard {row['expected']:<10} as {row['predicted']:<10} {row['frames']} frames")

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(
            {
                "checkpoint": str(args.checkpoint),
                "songs": len(held_back),
                "overall": overall,
                "perSong": sorted(per_song, key=lambda r: r[FULL]),
                "confusions": confusions(predicted, actual, top=25),
            },
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"\nFull report written to {args.report}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
