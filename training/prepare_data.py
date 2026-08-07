#!/usr/bin/env python3
"""Match annotations to your music, then turn the pairs into training features.

    python3 prepare_data.py --annotations data/annotations --audio ~/Music --out data/features

Run it once. It reports exactly which annotated songs it could not find in your library, so the
gap between "songs researchers annotated" and "songs you own" is visible rather than silent.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hearsay_training import dataset  # noqa: E402


def collect_annotations(root: Path) -> list[tuple[str, Path]]:
    found: list[tuple[str, Path]] = []
    for path in sorted(root.rglob("*")):
        if path.suffix.lower() in {".lab", ".txt"}:
            found.append((path.stem, path))
        elif path.suffix.lower() == ".json" and "jaah" in str(path).lower():
            found.append((path.stem, path))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--audio", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--limit", type=int, default=0, help="stop after N tracks, for a quick trial")
    args = parser.parse_args()

    args.out.mkdir(parents=True, exist_ok=True)

    print(f"Indexing audio under {args.audio} …")
    index = dataset.index_audio_library(args.audio)
    print(f"  {len(index)} audio files")

    annotations = collect_annotations(args.annotations)
    print(f"  {len(annotations)} annotation files")

    matched = 0
    missing: list[str] = []

    for name, annotation_path in annotations:
        audio_path = dataset.match_audio(name, index)
        if audio_path is None:
            missing.append(name)
            continue

        target = args.out / f"{dataset.normalize_title(name)}.npz"
        if target.exists():
            matched += 1
            continue

        try:
            if annotation_path.suffix.lower() == ".json":
                spans = dataset.read_jaah(annotation_path)
            else:
                spans = dataset.read_lab(annotation_path)
            if not spans:
                missing.append(f"{name} (empty annotation)")
                continue

            features = dataset.compute_cqt(audio_path)
            labels = dataset.labels_for_frames(spans, features.shape[0])
            np.savez_compressed(target, features=features, labels=labels)
            matched += 1
            print(f"  ✓ {name}  ({features.shape[0]} frames)")
        except Exception as error:  # noqa: BLE001 - one bad file must not stop the run
            missing.append(f"{name} ({error})")

        if args.limit and matched >= args.limit:
            break

    print()
    print(f"Prepared {matched} tracks into {args.out}")
    if missing:
        report = args.out / "missing.txt"
        report.write_text("\n".join(missing), encoding="utf-8")
        print(f"{len(missing)} annotated songs were not found in your library.")
        print(f"The list is in {report} — these are the recordings you would need to add.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
