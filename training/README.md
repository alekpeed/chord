# Training

Everything needed to train a chord recognition model on your own desktop and drop the result
into the Android app — and, first, to measure the recognizer already in it.

This runs on your machine, not the tablet. The tablet only ever loads the finished model.

## Measuring the analyzer you already have

Start here. Until this loop existed, the analyzer's accuracy had never been measured on any
material at all, and every claim about whether a change helped rested on proxy metrics — how many
chords per minute, how many carried color, where the changes fell in the bar. Those say a chart is
*shaped* like a real chart. None of them says a chord is *right*.

The capture apps solve the missing ground truth by construction: they name a chord, print the
notes, and only write a take whose keys match what was asked for. One command turns that into a
number.

```bash
./gradlew :tools:analyzer:installDist                    # once, and after any analyzer change

python3 measure_capture.py \
    --takes ~/hearsay-capture/takes.jsonl \
    --out data/measure \
    --backend fluidsynth \
    --label "before wiring BassTracker"
```

It renders the captured MIDI to audio, writes the Harte `.lab` truth beside it, runs the desktop
analyzer over it, and scores the charts with `evaluate_chart.py`. Everything lands in
`data/measure`: `audio/`, `charts/`, `scores.json`, and `measurement.json`, which carries the
scores together with what produced them. Run it before and after a change and the two
`measurement.json` files are the comparison.

The three steps are also separately usable — `render_takes.py` writes MIDI, audio and `.lab` and
nothing else, which is what you want when generating training material rather than scoring.

**Backends.** Install a soundfont, once:

```bash
sudo apt install fluidsynth fluid-soundfont-gm ffmpeg
```

`--backend auto`, the default, then plays the MIDI through that soundfont. It is the one to
trust: a General MIDI piano has the overtone structure, attack noise and decay of a real
instrument, which is what the front end has to survive.

Without a soundfont, `auto` falls back to `--backend synth`, the additive synthesizer in
`render_takes.py`. It has real harmonics, and it is not the easy case — it is the *harder* one, by
a lot. On one corpus through one analyzer it scored **3% root accuracy where the soundfont piano
scored 48%**. A score from it measures the analyzer against a tone generator and says almost
nothing about a piano. Both the summary and `measurement.json` record which backend actually ran,
and neither number should ever be quoted without it.

**What this does and does not measure.** Synthesized solo piano has no drums, no bass guitar, no
vocals, no room and no mix compression, and one chord at a time exercises chord identification
without exercising the decoder. A good score here does **not** mean the app works on records. A
bad score localizes the fault immediately, which is worth an afternoon.

**What is excluded, and why it is counted.** Power chords are dropped: Harte's `5` shorthand reads
back as `maj`, so writing one into a truth file would hand the analyzer a major third the player
never played. So are takes whose notes never sounded together — an arpeggio has no instant that is
the chord. Every exclusion is listed by take id in `render-manifest.json` and counted in the
summary. Nothing disappears quietly.

**How the truth is timed.** A take's truth span runs from its last onset to its first release —
the window where every note of the chord is sounding at once. A rolled chord is not yet the chord
during the roll, and it has stopped being the chord after the first key comes up; labeling either
would credit or penalize the analyzer for a chord that was not there. Time outside a span is
written nowhere, and the evaluator does not score what the truth file does not cover.

## Training a model on your own recordings

Everything below is the directory's second job: training a new recognizer rather than measuring
the one that ships. It needs a GPU and music you own.

## What you need

- Kubuntu (or any Ubuntu) with an NVIDIA card — 16 GB is comfortable, 8 GB is enough
- Music you own, as files on disk
- A few hours

## Five commands

```bash
cd training
./setup.sh                                    # once: drivers check, Python, PyTorch with CUDA

python3 download_annotations.py --out data/annotations
python3 prepare_data.py --annotations data/annotations --audio ~/Music --out data/features
python3 train.py --features data/features --out models
python3 evaluate.py --features data/features --checkpoint models/best.pt
```

Then export it for the app:

```bash
python3 export_onnx.py --checkpoint models/best.pt --out models/chord-recognizer.onnx
```

## What each step does

**`download_annotations.py`** fetches the chord labels researchers have published — text files
saying "0.0 to 2.3 seconds is G major". Free and legal to download. The *recordings* are not
included and never will be, for licensing reasons; you supply those.

**`prepare_data.py`** matches each annotation to a file in your music library, turns the audio
into a spectrogram, and lines up one chord label per frame. When it finishes it writes
`missing.txt` listing every annotated song it could not find — that list is exactly the
recordings you would need to add to make the model better.

**`train.py`** trains the model. It prints accuracy after every epoch and saves the best one so
far, so you can stop it whenever and still have something usable.

**`evaluate.py`** is the scoreboard. It scores against songs the model has never seen, three
ways — root correct, major/minor correct, exact chord correct — and lists the mistakes it makes
most often. Run it before and after any change so "better" is a number rather than an
impression.

**`export_onnx.py`** converts the trained model into ONNX, the format the Android app can load,
and writes a JSON card beside it with the vocabulary, feature settings and checksum that
`docs/model-registry.md` requires before a model is allowed in.

## How much music you actually need

The annotation sets cover well over a thousand songs, but you can only use the ones you own.
That is the real limit, not the GPU.

The Beatles set alone is 180 songs, which is enough to train a working model — because every
song is transposed into all twelve keys during training, turning 180 songs into roughly 2,000
training examples. The frequency axis is rolled and the chord labels rotate to match, so it
costs almost nothing.

| Songs you own | Roughly what to expect |
| --- | --- |
| 20–50 | Enough to prove the pipeline works end to end |
| 150–250 | A genuinely useful model, noticeably better than the current app |
| 500+ | Competitive with published research systems |

## What "good" looks like

Published systems of this kind reach roughly **80–85%** on major/minor chords across a test set.
The signal-processing recognizer currently in the app has been assumed to land around **60–70%**
on that measure. That figure is an assumption, not a measurement: nobody has scored it on real
music. `measure_capture.py` scores it on synthesized isolated chords and `evaluate.py` scores a
trained model on held-out songs; neither is a record with drums on it.

Exact-chord accuracy over the full vocabulary (sevenths, sixths, suspensions) is always lower,
around **65–75%** for good systems. Jazz is harder than pop, and dense mixes are harder than
sparse ones.

## Vocabulary

The model predicts 157 classes: 13 chord qualities at 12 roots, plus no-chord.

```
maj  min  dom7  min7  maj7  dim  aug  sus4  sus2  min6  maj6  dim7  hdim7
```

Class index is `quality index × 12 + root pitch class`. **That ordering is append-only** — it is
baked into every trained model, and reordering it would leave old models predicting the wrong
chord with no error anywhere.

Inversions and extensions above the ninth are deliberately outside the vocabulary. The
annotation sets are inconsistent about them, and a class the training data barely contains is a
class the model guesses at.

## Tests

```bash
python3 -m unittest discover training/tests
```

91 tests, no dependencies beyond numpy. They cover chord-notation parsing, frame alignment, the
pitch-shift transposition, the four scoring tiers, and the capture layout and renderer — the
places where a silent mistake would produce a confident number, or train the model on wrong
answers, without anything ever failing.

## Where this plugs into the app

The exported ONNX file and its JSON card go into a model pack. `docs/model-registry.md` lists
what has to be true before a model ships: benchmarked memory and runtime on a real tablet, a
defined fallback, and a quality classification shown to the user. A model that has not been
measured on the target hardware does not go in — including this one.
