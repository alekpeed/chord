# Training

Everything needed to train a chord recognition model on your own desktop and drop the result
into the Android app.

This runs on your machine, not the tablet. The tablet only ever loads the finished model.

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
python3 export_onnx.py --checkpoint models/best.pt --out models/chord-recogniser.onnx
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
The signal-processing recogniser currently in the app typically lands around **60–70%** on that
measure — though nobody has measured it on real music yet, which is what `evaluate.py` is for.

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

36 tests, no dependencies beyond numpy. They cover chord-notation parsing, frame alignment and
the pitch-shift transposition — the three places where a silent mistake would train the model on
wrong answers without anything ever failing.

## Where this plugs into the app

The exported ONNX file and its JSON card go into a model pack. `docs/model-registry.md` lists
what has to be true before a model ships: benchmarked memory and runtime on a real tablet, a
defined fallback, and a quality classification shown to the user. A model that has not been
measured on the target hardware does not go in — including this one.
