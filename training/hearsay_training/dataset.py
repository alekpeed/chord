"""Turning annotated recordings into training tensors.

The shape of the problem: an annotation file says which chord sounds between which seconds, and
the audio is yours. This module lines the two up, turns the audio into a constant-Q spectrogram,
and produces one chord label per frame.

Pitch-shift augmentation happens on the spectrogram rather than the audio. With 24 bins per
octave a semitone is exactly two bins, so shifting a song into another key is an array roll plus
a label rotation — no resampling, no re-annotation, and about a thousand times faster than
shifting the waveform.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from . import harte

SAMPLE_RATE = 22_050
HOP_LENGTH = 2048
BINS_PER_OCTAVE = 24
OCTAVES = 6
N_BINS = BINS_PER_OCTAVE * OCTAVES
FMIN_NOTE = "C1"

#: Frames per second of the feature sequence, ~10.8 at the settings above.
FRAME_RATE = SAMPLE_RATE / HOP_LENGTH

#: Semitones of pitch-shift augmentation applied either side of the original key.
AUGMENT_RANGE = 5

BINS_PER_SEMITONE = BINS_PER_OCTAVE // 12


@dataclass(frozen=True)
class Annotation:
    """One labeled span, in seconds."""

    start: float
    end: float
    class_index: int


@dataclass
class AnnotatedTrack:
    """An annotation file and the audio it describes, once the two have been matched."""

    identifier: str
    audio_path: Path
    annotations: list[Annotation]

    @property
    def duration(self) -> float:
        return self.annotations[-1].end if self.annotations else 0.0


def read_lab(path: Path) -> list[Annotation]:
    """Reads a `.lab` file: ``start end label`` per line, whitespace separated.

    Unreadable labels become no-chord rather than being dropped, because a gap in the timeline
    would silently shift every frame after it.
    """
    annotations: list[Annotation] = []
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        parts = line.split()
        if len(parts) < 3:
            continue
        try:
            start, end = float(parts[0]), float(parts[1])
        except ValueError:
            continue
        parsed = harte.parse(" ".join(parts[2:]))
        class_index = parsed.class_index if parsed else harte.NO_CHORD_INDEX
        annotations.append(Annotation(start, end, class_index))
    return annotations


def read_jaah(path: Path) -> list[Annotation]:
    """Reads the JAAH jazz dataset's JSON form."""
    document = json.loads(path.read_text(encoding="utf-8"))
    annotations: list[Annotation] = []
    for part in document.get("parts", []):
        starts = part.get("start_times") or []
        chords = part.get("chords") or []
        for index, chord in enumerate(chords):
            if index >= len(starts):
                break
            start = float(starts[index])
            end = float(starts[index + 1]) if index + 1 < len(starts) else start + 2.0
            parsed = harte.parse(chord)
            annotations.append(
                Annotation(start, end, parsed.class_index if parsed else harte.NO_CHORD_INDEX)
            )
    return annotations


def labels_for_frames(annotations: list[Annotation], frame_count: int) -> np.ndarray:
    """One class per frame, taking each frame's label from the span its center falls in."""
    labels = np.full(frame_count, harte.NO_CHORD_INDEX, dtype=np.int64)
    if not annotations:
        return labels

    centers = (np.arange(frame_count) + 0.5) / FRAME_RATE
    starts = np.array([a.start for a in annotations])
    classes = np.array([a.class_index for a in annotations], dtype=np.int64)
    ends = np.array([a.end for a in annotations])

    index = np.searchsorted(starts, centers, side="right") - 1
    valid = (index >= 0) & (centers < ends[np.clip(index, 0, len(ends) - 1)])
    labels[valid] = classes[index[valid]]
    return labels


def compute_cqt(audio_path: Path) -> np.ndarray:
    """Constant-Q spectrogram, log-compressed, shaped ``(frames, bins)``.

    Constant-Q rather than a plain FFT because its bins are spaced by musical interval, which is
    what makes the pitch-shift trick a simple roll and what makes the model's job about
    intervals rather than absolute frequency.
    """
    import librosa

    audio, _ = librosa.load(str(audio_path), sr=SAMPLE_RATE, mono=True)
    cqt = librosa.cqt(
        audio,
        sr=SAMPLE_RATE,
        hop_length=HOP_LENGTH,
        fmin=librosa.note_to_hz(FMIN_NOTE),
        n_bins=N_BINS,
        bins_per_octave=BINS_PER_OCTAVE,
    )
    magnitude = np.abs(cqt).astype(np.float32)
    compressed = np.log1p(50.0 * magnitude)
    return compressed.T  # (frames, bins)


def shift_features(features: np.ndarray, semitones: int) -> np.ndarray:
    """Transposes a spectrogram by rolling it along the frequency axis.

    Bins rolled in from outside the range are zeroed rather than wrapped: wrapping would fold
    the top of the spectrum onto the bass and teach the model harmony that was never played.
    """
    if semitones == 0:
        return features
    shift = semitones * BINS_PER_SEMITONE
    rolled = np.roll(features, shift, axis=1)
    if shift > 0:
        rolled[:, :shift] = 0.0
    else:
        rolled[:, shift:] = 0.0
    return rolled


def shift_labels(labels: np.ndarray, semitones: int) -> np.ndarray:
    if semitones == 0:
        return labels
    return np.array([harte.transpose_class(int(c), semitones) for c in labels], dtype=np.int64)


NORMALIZE_PATTERN = re.compile(r"[^a-z0-9]+")


def normalize_title(text: str) -> str:
    """Loosens a filename enough to match an annotation to a music file.

    Track numbers, underscores, punctuation and case all differ between an annotation set and
    somebody's music library; what survives is the words.
    """
    lowered = text.lower()
    lowered = re.sub(r"^\d+[\s\-_.]+", "", lowered)
    lowered = re.sub(r"\b(cd|disc)\s*\d+\b", "", lowered)
    return NORMALIZE_PATTERN.sub("", lowered)


AUDIO_SUFFIXES = {".flac", ".wav", ".mp3", ".m4a", ".ogg", ".aiff", ".aif", ".wma", ".opus"}


def index_audio_library(root: Path) -> dict[str, Path]:
    """Maps every audio file under [root] by its normalized name."""
    index: dict[str, Path] = {}
    for path in root.rglob("*"):
        if path.suffix.lower() not in AUDIO_SUFFIXES:
            continue
        key = normalize_title(path.stem)
        index.setdefault(key, path)
    return index


def match_audio(annotation_name: str, index: dict[str, Path]) -> Path | None:
    """Finds the recording an annotation describes, exactly or by containment."""
    key = normalize_title(annotation_name)
    if key in index:
        return index[key]
    for candidate_key, path in index.items():
        if len(key) >= 8 and (key in candidate_key or candidate_key in key):
            return path
    return None
