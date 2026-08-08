# Desktop analyzer

Runs the analysis on a Linux desktop instead of the tablet, and writes a chart file the app can
import.

It is not a port. `:core:audio` is a plain Kotlin module with no Android dependency — a constraint
kept from the first commit precisely so this could exist — so this runs the *same* code the tablet
runs, on the same recording, with a desktop's memory instead of a phone's.

## What it buys you

| | Tablet | Desktop |
| --- | --- | --- |
| Heap available | a few hundred MB | 12 GB by default here |
| Maximum Quality profile | not on a long track | yes |
| A folder of two hundred songs | one at a time, by hand | one command |

**Your GPU does nothing here.** This is FFTs, median filters and dynamic programming, not a neural
network. The GPU matters for one thing in this repository — training a model, in `../training`.

## Build it

Needs a JDK 17 or newer and ffmpeg.

```bash
sudo apt install default-jdk ffmpeg

cd /path/to/chord
./gradlew :tools:analyzer:installDist
```

That produces `tools/analyzer/build/install/analyzer/bin/analyzer`. Put it on your path if you like:

```bash
sudo ln -sf "$PWD/tools/analyzer/build/install/analyzer/bin/analyzer" /usr/local/bin/hearsay-analyze
```

## Use it

```bash
hearsay-analyze ~/Music/stevie/lovesinneed.flac

hearsay-analyze --profile maximum --out ~/charts ~/Music/stevie/

hearsay-analyze --text ~/Music/*.flac        # also writes a readable lead sheet
```

Options:

| | |
| --- | --- |
| `--out <folder>` | where to write charts (default: next to the audio) |
| `--profile fast\|balanced\|maximum` | default `maximum` — the reason to be on a desktop |
| `--text` | also write a plain-text lead sheet |
| `--force` | re-analyze files that already have a chart |

A folder is walked recursively. Files that already have a chart are skipped unless `--force`, so
re-running after adding music only does the new work. One unreadable file is reported and counted;
it does not stop the batch.

## Getting a chart onto the tablet

Each recording produces `<name>.hearsay.json`. Copy it across — USB, Syncthing, Drive, whatever you
already use — then in the app open the song and choose **Import a chart** on the analysis screen.

The file is structured, not a rendered lead sheet: every chord keeps its root, quality and
extensions separately, along with the confidence the analysis had in it and the runners-up it
considered. That is what lets you transpose it, correct it, and disagree with it on the tablet
exactly as if it had been analyzed there.

A chord you had already corrected by hand stays marked as yours through the round trip. Everything
else stays marked as the machine's opinion, because that is what it is.

## Accuracy

The same as the tablet's, because it is the same code — except that Maximum Quality is actually
reachable here, which uses an 8192-point transform instead of 4096 and helps most on dense mixes.

Real-world accuracy is still unmeasured against annotated recordings. `../training/evaluate.py` is
the thing that would measure it. Until that has been run, treat every number the tool prints as the
analysis's own opinion of itself.
