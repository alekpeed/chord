#!/usr/bin/env python3
"""Fetch the published chord annotations.

    python3 download_annotations.py --out data/annotations

These are the chord labels researchers published — free, and legal to download. The recordings
are not included and never will be; you supply those from your own collection, which is the same
constraint the app itself works under.

If a download fails, the project's home page is printed so you can fetch it by hand. A silently
skipped dataset would look like a small library rather than a broken URL.
"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tarfile
import urllib.error
import urllib.request
from pathlib import Path

SOURCES = [
    {
        "name": "isophonics-beatles",
        "songs": "180 Beatles songs",
        "kind": "tar",
        "url": "http://isophonics.net/files/annotations/The%20Beatles%20Annotations.tar.gz",
        "home": "http://isophonics.net/content/reference-annotations",
    },
    {
        "name": "isophonics-queen",
        "songs": "20 Queen songs",
        "kind": "tar",
        "url": "http://isophonics.net/files/annotations/Queen%20Annotations.tar.gz",
        "home": "http://isophonics.net/content/reference-annotations",
    },
    {
        "name": "isophonics-carole-king",
        "songs": "7 Carole King songs",
        "kind": "tar",
        "url": "http://isophonics.net/files/annotations/Carole%20King%20Annotations.tar.gz",
        "home": "http://isophonics.net/content/reference-annotations",
    },
    {
        "name": "jaah",
        "songs": "113 jazz recordings",
        "kind": "git",
        "url": "https://github.com/MTG/JAAH.git",
        "home": "https://github.com/MTG/JAAH",
    },
]

MANUAL = [
    (
        "McGill Billboard",
        "~740 chart hits, 1958-1991 — the biggest single source",
        "https://ddmal.music.mcgill.ca/research/The_McGill_Billboard_Project_(Chord_Analysis_Dataset)/",
        "Download the LAB annotations archive and unpack it into your annotations folder.",
    ),
    (
        "RWC Popular Music",
        "100 songs, audio included under a research license",
        "https://staff.aist.go.jp/m.goto/RWC-MDB/",
        "Audio is distributed on paid media for research use; annotations come from AIST.",
    ),
]


def download_tar(url: str, destination: Path) -> bool:
    archive = destination.parent / (destination.name + ".tar.gz")
    try:
        print(f"    downloading …")
        with urllib.request.urlopen(url, timeout=120) as response, archive.open("wb") as handle:
            shutil.copyfileobj(response, handle)
        destination.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as tar:
            tar.extractall(destination, filter="data")
        archive.unlink(missing_ok=True)
        return True
    except (urllib.error.URLError, tarfile.TarError, OSError, TimeoutError) as error:
        print(f"    failed: {error}")
        archive.unlink(missing_ok=True)
        return False


def clone(url: str, destination: Path) -> bool:
    if destination.exists():
        print("    already present")
        return True
    try:
        subprocess.run(
            ["git", "clone", "--depth", "1", url, str(destination)],
            check=True, capture_output=True, timeout=600,
        )
        return True
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError) as error:
        print(f"    failed: {error}")
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=Path("data/annotations"))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    succeeded, failed = [], []

    for source in SOURCES:
        destination = args.out / source["name"]
        print(f"\n{source['name']} — {source['songs']}")
        if destination.exists() and any(destination.iterdir()):
            print("    already present")
            succeeded.append(source)
            continue

        ok = clone(source["url"], destination) if source["kind"] == "git" \
            else download_tar(source["url"], destination)
        (succeeded if ok else failed).append(source)

    labs = list(args.out.rglob("*.lab")) + list(args.out.rglob("*.json"))
    print(f"\n{'=' * 70}")
    print(f"{len(labs)} annotation files in {args.out}")

    if failed:
        print("\nThese could not be downloaded — the links move from time to time.")
        print("Fetch them by hand and unpack into the same folder:")
        for source in failed:
            print(f"  · {source['name']}: {source['home']}")

    print("\nWorth adding by hand, and much the largest source:")
    for name, description, url, note in MANUAL:
        print(f"  · {name} — {description}")
        print(f"    {url}")
        print(f"    {note}")

    print("\nNext:  python3 prepare_data.py --annotations "
          f"{args.out} --audio ~/Music --out data/features")
    return 0 if succeeded else 1


if __name__ == "__main__":
    raise SystemExit(main())
