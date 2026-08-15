"""Turning captured takes into a timeline that can be rendered and scored.

The capture apps write one accepted take per line to ``takes.jsonl``: what the prompt asked for,
and every key that was pressed to answer it. Each take is a second or two of one chord, which is
not something an analyzer can be pointed at. This module lays those takes end to end on a fixed
grid so they become a handful of minutes-long *sheets*, each with a Harte ``.lab`` truth file
beside it — the shape :mod:`evaluate_chart` already scores.

Two decisions here are the whole point, and both are about not inventing agreement:

**A take's truth span is the window where every one of its notes sounds at once** — the last
onset to the first release, not the whole slot and not the whole gesture. A player rolls a chord;
labeling the roll as the full chord would credit the analyzer for a chord that was not yet
sounding, and labeling the silence after the release would penalize it for one that had stopped.
Time outside a truth span is written nowhere, and :mod:`evaluate_chart` does not score what the
truth file does not cover.

**A chord this vocabulary cannot spell is excluded and counted, never approximated.** Power
chords are the case: Harte's ``5`` shorthand reads back as ``maj``, so writing one would hand the
analyzer a major third the player never played.

Kept dependency-free, like the notation module it builds on: the layout is the part most likely
to be subtly wrong, and it should be testable with nothing installed.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from . import harte

#: Milliseconds of silence in front of the first chord of a sheet. The analyzer's onset detection
#: and beat tracking both need something to settle against; starting a file on a downbeat with no
#: run-up is a case real recordings never present.
DEFAULT_LEAD_IN_MS = 1_500

#: Milliseconds between one take's slot start and the next. Long enough that a piano's decay has
#: died away before the next chord, so a chord is scored against itself and not against the ring
#: of the one before it.
DEFAULT_SLOT_MS = 2_500

#: Takes per rendered sheet. Sixty at the default slot gives two-and-a-half minute files: long
#: enough for tempo and key estimation to run on something, short enough that one analyzer
#: invocation is a minute rather than an afternoon.
DEFAULT_TAKES_PER_SHEET = 60

#: Milliseconds of decay left unscored either side of a labeled silence, when gap labeling is on.
#: A piano does not stop when the key comes up.
DEFAULT_GAP_GUARD_MS = 400


@dataclass(frozen=True)
class Note:
    """One key, at the times the capture recorded, relative to nothing yet."""

    pitch: int
    velocity: int
    on_ms: int
    off_ms: int


@dataclass(frozen=True)
class Take:
    """One accepted take, as read from ``takes.jsonl``."""

    id: str
    block: str
    chord: dict
    voicing: str
    inversion: int
    extra_intervals: list[int]
    notes: list[Note]

    @property
    def label(self) -> str | None:
        """The Harte truth label, or ``None`` when this vocabulary cannot spell the chord."""
        return harte.label_of_structured(self.chord)


@dataclass(frozen=True)
class PlacedNote:
    pitch: int
    velocity: int
    start_ms: int
    end_ms: int


@dataclass(frozen=True)
class PlacedTake:
    """One take positioned on a sheet's timeline, with the window that may be scored."""

    take_id: str
    block: str
    label: str
    notes: list[PlacedNote]
    #: The span where every note of the take sounds together. Only this is written to the .lab.
    truth_start_ms: int
    truth_end_ms: int

    @property
    def sounding_start_ms(self) -> int:
        return min(note.start_ms for note in self.notes)

    @property
    def sounding_end_ms(self) -> int:
        return max(note.end_ms for note in self.notes)


@dataclass
class Sheet:
    """One renderable file: a run of takes, the audio's length, and what it is called."""

    name: str
    takes: list[PlacedTake] = field(default_factory=list)
    duration_ms: int = 0


@dataclass
class Excluded:
    """Takes that were read but not laid out, with the reason, so nothing vanishes quietly."""

    unspellable: list[str] = field(default_factory=list)
    empty: list[str] = field(default_factory=list)
    never_sounded_together: list[str] = field(default_factory=list)

    @property
    def total(self) -> int:
        return len(self.unspellable) + len(self.empty) + len(self.never_sounded_together)

    def describe(self) -> list[str]:
        lines = []
        if self.unspellable:
            lines.append(
                f"{len(self.unspellable)} take(s) excluded: no Harte spelling in this "
                "vocabulary (power chords). Scoring them would require inventing a third."
            )
        if self.empty:
            lines.append(f"{len(self.empty)} take(s) excluded: no notes recorded.")
        if self.never_sounded_together:
            lines.append(
                f"{len(self.never_sounded_together)} take(s) excluded: the notes never all "
                "sounded at once, so there is no window where the chord is what was played."
            )
        return lines


def read_takes(path: Path) -> tuple[list[Take], int]:
    """Reads ``takes.jsonl``, returning the takes and the number of unreadable lines.

    Unreadable lines are counted rather than raised on: a session that ended in a closed lid can
    leave half a line behind, and losing an hour of playing to one truncated write would be a
    worse failure than skipping it. They are returned so the caller can refuse a file that is
    mostly garbage instead of scoring against whatever survived.
    """
    takes: list[Take] = []
    unreadable = 0
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        try:
            row = json.loads(stripped)
            notes = [
                Note(
                    pitch=int(note["pitch"]),
                    velocity=int(note["velocity"]),
                    on_ms=int(note["onMs"]),
                    off_ms=int(note["offMs"]),
                )
                for note in row["notes"]
            ]
            takes.append(
                Take(
                    id=str(row["id"]),
                    block=str(row.get("block", "")),
                    chord=row["chord"],
                    voicing=str(row.get("voicing", "")),
                    inversion=int(row.get("inversion", 0)),
                    extra_intervals=[int(value) for value in row.get("extraIntervals", [])],
                    notes=notes,
                )
            )
        except (ValueError, KeyError, TypeError):
            unreadable += 1
    return takes, unreadable


def place(take: Take, slot_start_ms: int, slot_ms: int) -> PlacedTake | None:
    """Positions one take in its slot, preserving the timing the player actually produced.

    Onsets keep their offsets from each other, so a rolled or arpeggiated chord stays rolled —
    that is the material the analyzer has to cope with, and quantizing it away would measure a
    performance nobody gave. Releases are clipped to the slot so one held chord cannot bleed into
    the label of the next.

    Returns ``None`` when the take has no window in which all of its notes sound together.
    """
    if not take.notes:
        return None
    origin = min(note.on_ms for note in take.notes)
    limit = slot_start_ms + slot_ms
    placed: list[PlacedNote] = []
    for note in take.notes:
        start = slot_start_ms + (note.on_ms - origin)
        end = slot_start_ms + (note.off_ms - origin)
        if start >= limit:
            # A gesture longer than a slot: the tail is dropped rather than allowed to overlap
            # the next chord, and the truth window below shrinks to match what is left.
            continue
        placed.append(PlacedNote(note.pitch, note.velocity, start, min(end, limit)))
    if not placed:
        return None

    truth_start = max(note.start_ms for note in placed)
    truth_end = min(note.end_ms for note in placed)
    if truth_end <= truth_start:
        return None

    label = take.label
    if label is None:
        return None
    return PlacedTake(
        take_id=take.id,
        block=take.block,
        label=label,
        notes=placed,
        truth_start_ms=truth_start,
        truth_end_ms=truth_end,
    )


def plan(
    takes: list[Take],
    *,
    slot_ms: int = DEFAULT_SLOT_MS,
    takes_per_sheet: int = DEFAULT_TAKES_PER_SHEET,
    lead_in_ms: int = DEFAULT_LEAD_IN_MS,
    name_prefix: str = "sheet",
) -> tuple[list[Sheet], Excluded]:
    """Lays every take out on sheets, and reports what could not be laid out and why."""
    excluded = Excluded()
    usable: list[Take] = []
    for take in takes:
        if not take.notes:
            excluded.empty.append(take.id)
        elif take.label is None:
            excluded.unspellable.append(take.id)
        else:
            usable.append(take)

    sheets: list[Sheet] = []
    for index in range(0, len(usable), takes_per_sheet):
        batch = usable[index : index + takes_per_sheet]
        sheet = Sheet(name=f"{name_prefix}-{index // takes_per_sheet + 1:03d}")
        for position, take in enumerate(batch):
            placed = place(take, lead_in_ms + position * slot_ms, slot_ms)
            if placed is None:
                excluded.never_sounded_together.append(take.id)
                continue
            sheet.takes.append(placed)
        if not sheet.takes:
            continue
        # The tail is a whole slot past the last onset, so the final chord's decay is inside the
        # file. An analyzer handed a chord that stops at the last sample reports a shorter one.
        sheet.duration_ms = lead_in_ms + len(batch) * slot_ms
        sheets.append(sheet)
    return sheets, excluded


def truth_lines(
    sheet: Sheet, *, label_gaps: bool = False, gap_guard_ms: int = DEFAULT_GAP_GUARD_MS
) -> list[str]:
    """Writes a sheet's truth file: ``start end label``, seconds, one span per line.

    With ``label_gaps`` the quiet between chords is written as ``N`` so that hallucinating
    harmony over silence costs something. It is off by default because this material is roughly
    half silence by construction, and a no-chord agreement figure computed over that says more
    about the layout than about the analyzer.
    """
    lines = [
        "# Generated from a capture corpus by hearsay_training.capture.",
        "# Each span covers only the window where every note of the take sounds together.",
    ]
    spans: list[tuple[int, int, str]] = [
        (take.truth_start_ms, take.truth_end_ms, take.label) for take in sheet.takes
    ]

    if label_gaps:
        gaps: list[tuple[int, int, str]] = []
        edges = [(take.sounding_start_ms, take.sounding_end_ms) for take in sheet.takes]
        boundaries = [(0, 0)] + edges + [(sheet.duration_ms, sheet.duration_ms)]
        for (_, previous_end), (next_start, _) in zip(boundaries, boundaries[1:]):
            start = previous_end + gap_guard_ms
            end = next_start - gap_guard_ms
            if end > start:
                gaps.append((start, end, harte.NO_CHORD))
        spans += gaps

    for start_ms, end_ms, label in sorted(spans):
        lines.append(f"{start_ms / 1000:.3f} {end_ms / 1000:.3f} {label}")
    return lines
