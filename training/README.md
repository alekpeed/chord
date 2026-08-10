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

## Making music to train on

The annotation sets cover songs you mostly do not own, and the classes they do cover are the
common ones. `generate_corpus.py` goes the other way: write the chords first and produce audio
that plays them, so the label is exact by construction and rare qualities can be asked for
deliberately.

```bash
sudo apt install mma fluidsynth fluid-soundfont-gm
pip install mido

python3 generate_corpus.py --out data/generated --keys 12 --stems
python3 prepare_data.py --annotations data/generated/annotations \
    --audio data/generated/audio --out data/features
```

`mma` writes an arrangement — separate bass, drums and comping — from a chord chart, and
`fluidsynth` renders it through a SoundFont, which is recorded instruments rather than synthesis.
The built-in progressions cover all thirteen qualities, including the `dim`, `aug`, `sus2`,
`min6` and `hdim7` the published corpora are thinnest on.

**Nothing MMA does is taken on trust.** It has its own opinions about chord names, and two of them
would silently mislabel every affected track: `Cdim` is voiced as a diminished *seventh*, and a
groove that comps on beats one and three will not play a chord written on beat two — MMA accepts
four chords in a bar and plays two. Most grooves also anticipate, comping the second chord of a
bar a whole beat before the chart says it starts. So every track is read back and checked against
the chart that made it, and one whose audio disagrees with its label is refused rather than
written with a warning. The reasons land in `rejected.txt`; each is a chart or a groove to fix.

`--stems` additionally writes each instrument on its own. These are not separated audio and carry
none of the artifacts that word implies: the parts were never mixed, so the stems are exact,
sample-aligned, and sum back to the mix. That is what makes them worth having for testing whether
combining predictions across mix, accompaniment and bass is worth anything — an idea a stem
separator's own damage makes impossible to test cleanly.

What this does not give you is a finished record: a SoundFont is dry and unmixed, nobody is
singing, and MMA's styles are its own combo idiom. It is coverage of chords real corpora are thin
on, to be mixed with real recordings rather than to replace them.

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
The signal-processing recognizer currently in the app typically lands around **60–70%** on that
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
