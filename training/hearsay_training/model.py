"""The chord recognition model.

A convolutional front end followed by a bidirectional recurrent layer — the shape that has been
standard for this task for a decade, and small enough that a 16 GB card trains it in hours
rather than days.

Why this shape. The convolutions look at a short window of the spectrogram and learn what a
chord *sounds* like regardless of where it sits in time. The recurrent layer then looks along
the whole song, which is what lets it learn that chords hold for a while and change on bar lines
— the same job the hand-written Viterbi decoder does in the app, except learned from real music
rather than assumed.
"""

from __future__ import annotations

import torch
from torch import nn

from .dataset import N_BINS
from .harte import NUM_CLASSES


class ChordRecognizer(nn.Module):
    def __init__(
        self,
        num_classes: int = NUM_CLASSES,
        input_bins: int = N_BINS,
        channels: int = 32,
        hidden: int = 128,
        dropout: float = 0.3,
    ):
        super().__init__()

        # Frequency-only pooling: time resolution is what tells us where a chord changes, so it
        # is never reduced. Only the frequency axis is compressed.
        self.features = nn.Sequential(
            nn.Conv2d(1, channels, kernel_size=(3, 3), padding=(1, 1)),
            nn.BatchNorm2d(channels),
            nn.ReLU(inplace=True),
            nn.Conv2d(channels, channels, kernel_size=(3, 3), padding=(1, 1)),
            nn.BatchNorm2d(channels),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=(1, 2)),
            nn.Dropout(dropout),
            nn.Conv2d(channels, channels * 2, kernel_size=(3, 3), padding=(1, 1)),
            nn.BatchNorm2d(channels * 2),
            nn.ReLU(inplace=True),
            nn.MaxPool2d(kernel_size=(1, 2)),
            nn.Dropout(dropout),
        )

        reduced_bins = input_bins // 4
        self.recurrent = nn.GRU(
            input_size=channels * 2 * reduced_bins,
            hidden_size=hidden,
            num_layers=2,
            batch_first=True,
            bidirectional=True,
            dropout=dropout,
        )
        self.classifier = nn.Linear(hidden * 2, num_classes)

    def forward(self, spectrogram: torch.Tensor) -> torch.Tensor:
        """``(batch, frames, bins)`` in, ``(batch, frames, classes)`` of logits out."""
        x = spectrogram.unsqueeze(1)          # (batch, 1, frames, bins)
        x = self.features(x)                  # (batch, channels, frames, bins/4)
        batch, channels, frames, bins = x.shape
        x = x.permute(0, 2, 1, 3).reshape(batch, frames, channels * bins)
        x, _ = self.recurrent(x)
        return self.classifier(x)


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters() if p.requires_grad)
