"""Reading the chord notation the research datasets are written in.

Every published chord-annotation set — Isophonics, Billboard, JAAH — uses Harte notation:
``C:maj7``, ``G:7/3``, ``F#:min``, ``N`` for no chord. This module turns that into the same
vocabulary the Android app recognises, so a model trained here produces labels the app can
already display and store.

Kept dependency-free on purpose: it is the piece most likely to be wrong, and it should be
testable with nothing installed.
"""

from __future__ import annotations

from dataclasses import dataclass

PITCH_CLASSES = {
    "C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11,
}

# The qualities the app can name, in the order that fixes their class indices forever.
# Changing this order invalidates every trained model, so it is append-only.
QUALITIES: list[str] = [
    "maj",
    "min",
    "dom7",
    "min7",
    "maj7",
    "dim",
    "aug",
    "sus4",
    "sus2",
    "min6",
    "maj6",
    "dim7",
    "hdim7",
]

NO_CHORD = "N"
NO_CHORD_INDEX = len(QUALITIES) * 12

#: Total class count: every quality at every root, plus "no chord".
NUM_CLASSES = NO_CHORD_INDEX + 1

# Harte shorthand -> our quality. Anything not here is reduced by its interval list below.
SHORTHAND = {
    "maj": "maj",
    "min": "min",
    "dim": "dim",
    "aug": "aug",
    "maj7": "maj7",
    "min7": "min7",
    "7": "dom7",
    "dim7": "dim7",
    "hdim7": "hdim7",
    "minmaj7": "min7",
    "maj6": "maj6",
    "min6": "min6",
    "9": "dom7",
    "maj9": "maj7",
    "min9": "min7",
    "11": "dom7",
    "min11": "min7",
    "13": "dom7",
    "maj13": "maj7",
    "min13": "min7",
    "sus2": "sus2",
    "sus4": "sus4",
    "7sus4": "sus4",
    "sus4(b7)": "sus4",
    "1": "maj",
    "5": "maj",
    # Our own canonical names, so a label this module writes can be read back by it. Without
    # this, `class_label` produced "C:dom7" and `parse` refused it — a round trip that silently
    # dropped every dominant chord.
    "dom7": "dom7",
}

# Interval sets, used when an annotation spells a chord out instead of naming it.
INTERVAL_QUALITIES: list[tuple[frozenset[int], str]] = [
    (frozenset({0, 4, 7, 10}), "dom7"),
    (frozenset({0, 4, 7, 11}), "maj7"),
    (frozenset({0, 3, 7, 10}), "min7"),
    (frozenset({0, 3, 6, 10}), "hdim7"),
    (frozenset({0, 3, 6, 9}), "dim7"),
    (frozenset({0, 4, 7, 9}), "maj6"),
    (frozenset({0, 3, 7, 9}), "min6"),
    (frozenset({0, 4, 7}), "maj"),
    (frozenset({0, 3, 7}), "min"),
    (frozenset({0, 3, 6}), "dim"),
    (frozenset({0, 4, 8}), "aug"),
    (frozenset({0, 5, 7}), "sus4"),
    (frozenset({0, 2, 7}), "sus2"),
]

DEGREE_SEMITONES = {
    "1": 0, "2": 2, "3": 4, "4": 5, "5": 7, "6": 9, "7": 11,
    "8": 12, "9": 14, "10": 16, "11": 17, "12": 19, "13": 21,
}


@dataclass(frozen=True)
class ParsedChord:
    """A chord reduced to the vocabulary the app shares with the model."""

    root: int
    quality: str

    @property
    def class_index(self) -> int:
        return QUALITIES.index(self.quality) * 12 + self.root

    @property
    def label(self) -> str:
        names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
        return f"{names[self.root]}:{self.quality}"


def parse_note(text: str) -> int | None:
    """Reads a note name with any number of sharps or flats into a pitch class."""
    if not text:
        return None
    letter = text[0].upper()
    if letter not in PITCH_CLASSES:
        return None
    value = PITCH_CLASSES[letter]
    for char in text[1:]:
        if char == "#":
            value += 1
        elif char == "b":
            value -= 1
        else:
            return None
    return value % 12


def _degree_semitones(degree: str) -> int | None:
    """Reads a Harte interval such as ``b7`` or ``#11`` into semitones above the root."""
    offset = 0
    index = 0
    while index < len(degree) and degree[index] in "#b":
        offset += 1 if degree[index] == "#" else -1
        index += 1
    number = degree[index:]
    if number not in DEGREE_SEMITONES:
        return None
    return (DEGREE_SEMITONES[number] + offset) % 12


def parse(label: str) -> ParsedChord | None:
    """Parses one Harte label.

    Returns ``None`` for no-chord and for anything unreadable — the caller decides whether an
    unreadable annotation is a skipped frame or a broken file, and silently guessing a chord
    would poison the training set.
    """
    text = label.strip()
    if not text or text in {NO_CHORD, "X", "&pause"}:
        return None

    # A slash bass says which note is lowest, not which chord it is; the vocabulary here has no
    # inversions, so the bass is read and discarded.
    text = text.split("/")[0]

    if ":" in text:
        root_text, quality_text = text.split(":", 1)
    else:
        root_text, quality_text = text, "maj"

    root = parse_note(root_text)
    if root is None:
        return None

    quality = _reduce_quality(quality_text)
    if quality is None:
        return None
    return ParsedChord(root=root, quality=quality)


def _reduce_quality(quality_text: str) -> str | None:
    text = quality_text.strip()
    if not text:
        return "maj"

    # `maj7(9)` and friends: the parenthesised extensions do not change the class.
    base = text.split("(")[0].strip()
    if base in SHORTHAND:
        return SHORTHAND[base]

    # A spelled-out interval list, e.g. `(1,b3,5,b7)`.
    if text.startswith("(") and text.endswith(")"):
        degrees = [d.strip() for d in text[1:-1].split(",") if d.strip()]
        semitones = {0}
        for degree in degrees:
            value = _degree_semitones(degree)
            if value is None:
                return None
            semitones.add(value)
        return _quality_of_intervals(semitones)

    if base:
        # Shorthand plus a spelled interval list, e.g. `min7(11)`; the shorthand wins.
        return SHORTHAND.get(base)
    return None


def _quality_of_intervals(semitones: set[int]) -> str | None:
    """Picks the richest quality whose notes are all present."""
    for intervals, quality in INTERVAL_QUALITIES:
        if intervals <= semitones:
            return quality
    return None


def transpose_class(class_index: int, semitones: int) -> int:
    """Shifts a class index by [semitones], leaving no-chord alone.

    This is what makes pitch-shift augmentation cheap: the audio is rotated in the frequency
    axis and the labels are rotated here, with no re-annotation and no resampling.
    """
    if class_index == NO_CHORD_INDEX:
        return NO_CHORD_INDEX
    quality_index, root = divmod(class_index, 12)
    return quality_index * 12 + (root + semitones) % 12


def class_label(class_index: int) -> str:
    if class_index == NO_CHORD_INDEX:
        return NO_CHORD
    quality_index, root = divmod(class_index, 12)
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    return f"{names[root]}:{QUALITIES[quality_index]}"


def to_app_symbol(class_index: int) -> str:
    """Renders a class the way the Android app writes chord symbols."""
    if class_index == NO_CHORD_INDEX:
        return "N.C."
    quality_index, root = divmod(class_index, 12)
    names = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
    suffix = {
        "maj": "", "min": "m", "dom7": "7", "min7": "m7", "maj7": "maj7",
        "dim": "dim", "aug": "aug", "sus4": "sus4", "sus2": "sus2",
        "min6": "m6", "maj6": "6", "dim7": "dim7", "hdim7": "m7b5",
    }[QUALITIES[quality_index]]
    return f"{names[root]}{suffix}"
