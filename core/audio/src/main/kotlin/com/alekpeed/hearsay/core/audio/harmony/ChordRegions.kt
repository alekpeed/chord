package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.model.timeline.ChordEvent

/**
 * How short a region has to be before it is not a chord at all.
 *
 * There are two different things a brief chord region can be, and they need opposite treatment. A
 * passing chord on the second half of a beat is music, and removing it is the failure this product
 * exists to avoid. A region lasting a fifth of a beat is not music: nobody plays a harmony for
 * 140 ms and no chart would write one. It is the decoder changing its mind between two frames.
 *
 * Reported from a tablet, in "Knocks Me Off My Feet" at 89 BPM: an E minor between a Cmaj7 and a
 * Gm, lasting 0.14 seconds — a fifth of a beat. The absorption pass ahead of this one could not
 * remove it, because that pass only absorbs a region into a neighbor sharing its root, and Em
 * shares no root with either side. It was written for a chord decaying across a bar line, where
 * the artifact is the same chord with an extra tone; it was never able to see this.
 *
 * The threshold is in beats rather than milliseconds because "too short to be a chord" is a
 * musical statement, not a physical one: 300 ms is half a beat in a ballad and two beats in a
 * hardcore tune.
 */
internal const val UnplayableChordBeats = 0.45f

/**
 * A short reading that introduces no pitch class outside a longer neighboring harmony is ambiguous
 * by construction. For example, Em contains only E-G-B, all already present in Cmaj7. With no new
 * harmonic evidence, temporal continuity wins rather than re-rooting the entire chord for one beat.
 * This is intentionally much narrower than generic smoothing: a genuine quick chord that introduces
 * even one new pitch class is untouched.
 */
private const val AmbiguousSubsetBeats = 1.15f

/**
 * Removes regions too short to be harmony, giving their time to the region before them.
 *
 * It also removes one narrow class of longer ambiguity: a brief strict pitch-class subset of a
 * neighboring harmony that lasts at least twice as long. That prevents Cmaj7 -> Em -> Cmaj7 style
 * re-rooting without erasing passing diminished, altered, or other quick chords that contribute new
 * harmonic information.
 */
internal fun dropUnplayableRegions(events: List<ChordEvent>, beatMs: Long): List<ChordEvent> {
    if (events.size < 2) return events
    val floor = (beatMs * UnplayableChordBeats).toLong().coerceAtLeast(1)
    val subsetFloor = (beatMs * AmbiguousSubsetBeats).toLong().coerceAtLeast(floor)

    val result = mutableListOf<ChordEvent>()
    var carriedStart: Long? = null

    for ((index, event) in events.withIndex()) {
        val previous = result.lastOrNull()
        val next = events.getOrNull(index + 1)
        val shortSubset = event.durationMs <= subsetFloor

        if (shortSubset && isStrictSubsetOfLonger(event, previous)) {
            result[result.lastIndex] = previous!!.copy(endMs = event.endMs)
            continue
        }

        if (shortSubset && isStrictSubsetOfLonger(event, next)) {
            carriedStart = carriedStart ?: event.startMs
            continue
        }

        if (event.durationMs < floor) {
            if (previous != null) {
                result[result.lastIndex] = previous.copy(endMs = event.endMs)
            } else {
                // Nothing before it to absorb it, so the region after inherits its start rather
                // than the recording opening on a silent gap.
                carriedStart = carriedStart ?: event.startMs
            }
            continue
        }
        val start = carriedStart ?: event.startMs
        carriedStart = null
        result += if (start != event.startMs) event.copy(startMs = start) else event
    }

    // Everything was an artifact. Better the original reading than an empty chart.
    return result.ifEmpty { events }
}

private fun isStrictSubsetOfLonger(candidate: ChordEvent, host: ChordEvent?): Boolean {
    val candidateChord = candidate.chord ?: return false
    val hostChord = host?.chord ?: return false
    if (host.durationMs < candidate.durationMs * 2) return false

    val candidatePitches = candidateChord.copy(bass = null).pitchClasses()
    val hostPitches = hostChord.copy(bass = null).pitchClasses()
    return candidatePitches.size < hostPitches.size && hostPitches.containsAll(candidatePitches)
}

/**
 * Joins neighbors that decoded to the same chord.
 *
 * Run again after artifacts are removed: an artifact between two regions of one chord was hiding
 * that they were one chord all along, which is most of what makes a chart say Cmaj7, Em, Cmaj7
 * where a musician would write one bar of Cmaj7.
 */
internal fun joinRepeatedRegions(events: List<ChordEvent>): List<ChordEvent> {
    if (events.size < 2) return events
    val result = mutableListOf<ChordEvent>()
    for (event in events) {
        val previous = result.lastOrNull()
        if (previous != null && previous.chord == event.chord) {
            result[result.lastIndex] = previous.copy(
                endMs = event.endMs,
                confidence = maxOf(previous.confidence, event.confidence),
            )
        } else {
            result += event
        }
    }
    return result
}
