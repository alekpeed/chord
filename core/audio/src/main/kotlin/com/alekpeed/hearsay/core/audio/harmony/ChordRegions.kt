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
 * Removes regions too short to be harmony, giving their time to the region before them.
 *
 * Backwards on purpose. The chord that follows an artifact keeps its own start, so it still lands
 * where it lands against the bar — which is the concern that stopped the earlier absorption pass
 * from ever crossing roots, and it is a real one. Extending the previous region forwards costs
 * nothing: that chord was sounding through the artifact anyway, which is why the decoder had to
 * change its mind to produce one.
 */
internal fun dropUnplayableRegions(events: List<ChordEvent>, beatMs: Long): List<ChordEvent> {
    if (events.size < 2) return events
    val floor = (beatMs * UnplayableChordBeats).toLong().coerceAtLeast(1)

    val result = mutableListOf<ChordEvent>()
    var carriedStart: Long? = null

    for (event in events) {
        if (event.durationMs < floor) {
            val previous = result.lastOrNull()
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
