#!/usr/bin/env python3
"""Train the chord recogniser.

    python3 train.py --features data/features --out models/

Runs on the GPU if there is one. On a 16 GB RTX with a couple of hundred songs this is a few
hours, and it checkpoints after every epoch, so stopping it early still leaves you a model.
"""

from __future__ import annotations

import argparse
import json
import random
import sys
import time
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.utils.data import DataLoader, Dataset

sys.path.insert(0, str(Path(__file__).resolve().parent))

from hearsay_training import dataset as data_utils  # noqa: E402
from hearsay_training.harte import NO_CHORD_INDEX, NUM_CLASSES  # noqa: E402
from hearsay_training.model import ChordRecogniser, count_parameters  # noqa: E402

SEQUENCE_FRAMES = 256  # about 24 seconds, long enough to learn that chords persist


class ChordDataset(Dataset):
    """Fixed-length excerpts, transposed on the fly.

    Augmentation is applied when an excerpt is drawn rather than precomputed, so every epoch
    sees each song in a different key and the whole thing still fits on disk.
    """

    def __init__(self, files: list[Path], augment: bool):
        self.files = files
        self.augment = augment
        self.cache: dict[Path, tuple[np.ndarray, np.ndarray]] = {}

    def __len__(self) -> int:
        return len(self.files)

    def _load(self, path: Path) -> tuple[np.ndarray, np.ndarray]:
        if path not in self.cache:
            with np.load(path) as archive:
                self.cache[path] = (archive["features"], archive["labels"])
        return self.cache[path]

    def __getitem__(self, index: int):
        features, labels = self._load(self.files[index])

        if features.shape[0] > SEQUENCE_FRAMES:
            start = random.randint(0, features.shape[0] - SEQUENCE_FRAMES)
        else:
            start = 0
        excerpt = features[start:start + SEQUENCE_FRAMES]
        excerpt_labels = labels[start:start + SEQUENCE_FRAMES]

        if excerpt.shape[0] < SEQUENCE_FRAMES:
            pad = SEQUENCE_FRAMES - excerpt.shape[0]
            excerpt = np.pad(excerpt, ((0, pad), (0, 0)))
            excerpt_labels = np.pad(excerpt_labels, (0, pad), constant_values=NO_CHORD_INDEX)

        if self.augment:
            semitones = random.randint(-data_utils.AUGMENT_RANGE, data_utils.AUGMENT_RANGE)
            excerpt = data_utils.shift_features(excerpt, semitones)
            excerpt_labels = data_utils.shift_labels(excerpt_labels, semitones)

        return torch.from_numpy(excerpt.copy()), torch.from_numpy(excerpt_labels.copy())


def split_files(features_dir: Path, validation_fraction: float, seed: int):
    files = sorted(features_dir.glob("*.npz"))
    if not files:
        raise SystemExit(f"No prepared features in {features_dir}. Run prepare_data.py first.")
    random.Random(seed).shuffle(files)
    cut = max(1, int(len(files) * validation_fraction))
    # Split by song, never by excerpt: two excerpts of the same recording in different splits
    # would let the model score well by memorising the song rather than learning chords.
    return files[cut:], files[:cut]


def evaluate(model: nn.Module, loader: DataLoader, device: torch.device) -> tuple[float, float]:
    model.eval()
    total_loss = 0.0
    correct = 0
    counted = 0
    criterion = nn.CrossEntropyLoss()

    with torch.no_grad():
        for features, labels in loader:
            features, labels = features.to(device), labels.to(device)
            logits = model(features)
            loss = criterion(logits.reshape(-1, NUM_CLASSES), labels.reshape(-1))
            total_loss += loss.item()

            predicted = logits.argmax(dim=-1)
            # No-chord frames are excluded from accuracy: a song with long silences would
            # otherwise score well for predicting nothing.
            mask = labels != NO_CHORD_INDEX
            correct += ((predicted == labels) & mask).sum().item()
            counted += mask.sum().item()

    return total_loss / max(1, len(loader)), correct / max(1, counted)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--features", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=Path("models"))
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--learning-rate", type=float, default=1e-3)
    parser.add_argument("--validation-fraction", type=float, default=0.15)
    parser.add_argument("--seed", type=int, default=1234)
    args = parser.parse_args()

    torch.manual_seed(args.seed)
    random.seed(args.seed)
    args.out.mkdir(parents=True, exist_ok=True)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if device.type == "cuda":
        print(f"Training on {torch.cuda.get_device_name(0)}")
    else:
        print("No GPU found — training on CPU, which will be slow.")

    train_files, validation_files = split_files(args.features, args.validation_fraction, args.seed)
    print(f"{len(train_files)} songs to train on, {len(validation_files)} held back to score against")

    train_loader = DataLoader(
        ChordDataset(train_files, augment=True),
        batch_size=args.batch_size, shuffle=True, num_workers=2, drop_last=True,
    )
    validation_loader = DataLoader(
        ChordDataset(validation_files, augment=False),
        batch_size=args.batch_size, shuffle=False, num_workers=2,
    )

    model = ChordRecogniser().to(device)
    print(f"{count_parameters(model):,} parameters")

    optimiser = torch.optim.AdamW(model.parameters(), lr=args.learning_rate, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimiser, T_max=args.epochs)
    criterion = nn.CrossEntropyLoss()

    best_accuracy = 0.0
    history = []

    for epoch in range(1, args.epochs + 1):
        model.train()
        started = time.time()
        running = 0.0

        for features, labels in train_loader:
            features, labels = features.to(device), labels.to(device)
            optimiser.zero_grad()
            logits = model(features)
            loss = criterion(logits.reshape(-1, NUM_CLASSES), labels.reshape(-1))
            loss.backward()
            nn.utils.clip_grad_norm_(model.parameters(), 5.0)
            optimiser.step()
            running += loss.item()

        scheduler.step()
        validation_loss, accuracy = evaluate(model, validation_loader, device)
        elapsed = time.time() - started

        print(
            f"epoch {epoch:3d}  train loss {running / max(1, len(train_loader)):.4f}  "
            f"val loss {validation_loss:.4f}  val accuracy {accuracy * 100:.1f}%  ({elapsed:.0f}s)"
        )
        history.append({"epoch": epoch, "val_loss": validation_loss, "val_accuracy": accuracy})

        torch.save(model.state_dict(), args.out / "last.pt")
        if accuracy > best_accuracy:
            best_accuracy = accuracy
            torch.save(model.state_dict(), args.out / "best.pt")
            print(f"          ↑ best so far, saved to {args.out / 'best.pt'}")

    (args.out / "history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
    print(f"\nBest validation accuracy: {best_accuracy * 100:.1f}%")
    print(f"Now run:  python3 export_onnx.py --checkpoint {args.out / 'best.pt'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
