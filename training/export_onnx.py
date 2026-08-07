#!/usr/bin/env python3
"""Export a trained checkpoint to ONNX, ready for the Android app.

    python3 export_onnx.py --checkpoint models/best.pt --out models/chord-recognizer.onnx

Writes the model beside a JSON card describing its vocabulary, feature settings and checksum —
the metadata `docs/model-registry.md` requires before a model is allowed into the app.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hearsay_training import dataset, harte  # noqa: E402
from hearsay_training.model import ChordRecognizer  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("models/chord-recognizer.onnx"))
    parser.add_argument("--version", default="1.0.0")
    parser.add_argument("--opset", type=int, default=17)
    args = parser.parse_args()

    args.out.parent.mkdir(parents=True, exist_ok=True)

    model = ChordRecognizer()
    model.load_state_dict(torch.load(args.checkpoint, map_location="cpu"))
    model.eval()

    # The frame count is dynamic so the app can hand it a whole song or one bar. The bin count
    # is fixed, because it is a property of the feature extraction both sides must agree on.
    example = torch.randn(1, 256, dataset.N_BINS)

    torch.onnx.export(
        model,
        example,
        str(args.out),
        input_names=["cqt"],
        output_names=["logits"],
        dynamic_axes={"cqt": {0: "batch", 1: "frames"}, "logits": {0: "batch", 1: "frames"}},
        opset_version=args.opset,
    )

    digest = hashlib.sha256(args.out.read_bytes()).hexdigest()
    card = {
        "id": "chord-recognizer-crnn",
        "version": args.version,
        "runtime": "onnx",
        "quality": "preview",
        "checksum": f"sha256:{digest}",
        "sizeBytes": args.out.stat().st_size,
        "input": {
            "name": "cqt",
            "shape": ["batch", "frames", dataset.N_BINS],
            "sampleRate": dataset.SAMPLE_RATE,
            "hopLength": dataset.HOP_LENGTH,
            "binsPerOctave": dataset.BINS_PER_OCTAVE,
            "fmin": dataset.FMIN_NOTE,
            "compression": "log1p(50 * magnitude)",
        },
        "output": {
            "name": "logits",
            "classes": harte.NUM_CLASSES,
            "qualities": harte.QUALITIES,
            "layout": "class index = quality index * 12 + root pitch class; last index is no-chord",
            "labels": [harte.class_label(i) for i in range(harte.NUM_CLASSES)],
        },
        # Written so the Android side can never disagree with the trainer about what a class
        # index means. If these two drift, every chord comes out wrong by a constant offset.
        "appSymbols": [harte.to_app_symbol(i) for i in range(harte.NUM_CLASSES)],
    }

    card_path = args.out.with_suffix(".json")
    card_path.write_text(json.dumps(card, indent=2), encoding="utf-8")

    print(f"Wrote {args.out}  ({args.out.stat().st_size / 1e6:.1f} MB)")
    print(f"Wrote {card_path}")
    print("\nCopy both into the app's model pack directory to use them.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
