package com.alekpeed.hearsay.core.model.timeline

import com.alekpeed.hearsay.core.model.music.Key

/**
 * Everything the performance view needs to follow a song: the chord regions, the beat grid, the
 * sections and the tempo map, indexed for lookup by playback position.
 *
 * All lists are held sorted by time. Use [of] to build one from unsorted analysis output.
 */
class SongChart private constructor(
    val chordEvents: List<ChordEvent>,
    val beats: List<BeatEvent>,
    val sections: List<SectionEvent>,
    val tempoSegments: List<TempoSegment>,
    val key: Key?,
) {

    val isEmpty: Boolean get() = chordEvents.isEmpty() && beats.isEmpty()

    val durationMs: Long
        get() = maxOf(
            chordEvents.lastOrNull()?.endMs ?: 0L,
            sections.lastOrNull()?.endMs ?: 0L,
            beats.lastOrNull()?.timeMs ?: 0L,
        )

    // ---- chords -------------------------------------------------------------------------------

    /** Index of the chord region containing [timeMs], or -1 when the position falls in a gap. */
    fun indexOfChordAt(timeMs: Long): Int {
        val candidate = floorIndex(chordEvents.size) { chordEvents[it].startMs <= timeMs }
        if (candidate < 0) return -1
        return if (timeMs < chordEvents[candidate].endMs) candidate else -1
    }

    fun chordAt(timeMs: Long): ChordEvent? = chordEvents.getOrNull(indexOfChordAt(timeMs))

    /** The chord region a seek should land on: the one containing [timeMs], else the next one. */
    fun indexOfChordAtOrAfter(timeMs: Long): Int {
        val containing = indexOfChordAt(timeMs)
        if (containing >= 0) return containing
        val next = chordEvents.indexOfFirst { it.startMs >= timeMs }
        return next
    }

    // ---- beats and measures -------------------------------------------------------------------

    fun beatAt(timeMs: Long): BeatEvent? {
        val index = floorIndex(beats.size) { beats[it].timeMs <= timeMs }
        return beats.getOrNull(index)
    }

    fun measureNumberAt(timeMs: Long): Int? = beatAt(timeMs)?.measureNumber

    fun sectionAt(timeMs: Long): SectionEvent? =
        sections.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }

    fun tempoAt(timeMs: Long): Float? =
        tempoSegments.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }?.bpm
            ?: tempoSegments.lastOrNull()?.bpm

    fun measureStartMs(measureNumber: Int): Long? =
        beats.firstOrNull { it.measureNumber == measureNumber && it.isDownbeat }?.timeMs

    fun nextMeasureStartMs(fromMs: Long): Long? =
        beats.firstOrNull { it.isDownbeat && it.timeMs > fromMs }?.timeMs

    /**
     * Where "previous measure" should seek to.
     *
     * Inside [restartWindowMs] of the current downbeat the jump goes to the measure before, which
     * is what a player pressing back twice in quick succession means. Later than that it restarts
     * the current measure, matching how transport buttons behave on hardware.
     */
    fun previousMeasureStartMs(fromMs: Long, restartWindowMs: Long = DefaultRestartWindowMs): Long? {
        val downbeats = beats.filter { it.isDownbeat }
        if (downbeats.isEmpty()) return null
        val currentIndex = downbeats.indexOfLast { it.timeMs <= fromMs }
        if (currentIndex < 0) return downbeats.first().timeMs
        val current = downbeats[currentIndex]
        val restartCurrent = fromMs - current.timeMs > restartWindowMs
        return when {
            restartCurrent -> current.timeMs
            currentIndex > 0 -> downbeats[currentIndex - 1].timeMs
            else -> current.timeMs
        }
    }

    fun withChordEvents(events: List<ChordEvent>): SongChart =
        of(events, beats, sections, tempoSegments, key)

    /** Largest index for which [predicate] holds, assuming it holds for a prefix of the list. */
    private inline fun floorIndex(size: Int, predicate: (Int) -> Boolean): Int {
        var low = 0
        var high = size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (predicate(mid)) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    companion object {
        const val DefaultRestartWindowMs = 400L

        val Empty = SongChart(emptyList(), emptyList(), emptyList(), emptyList(), null)

        fun of(
            chordEvents: List<ChordEvent> = emptyList(),
            beats: List<BeatEvent> = emptyList(),
            sections: List<SectionEvent> = emptyList(),
            tempoSegments: List<TempoSegment> = emptyList(),
            key: Key? = null,
        ): SongChart = SongChart(
            chordEvents = chordEvents.sortedBy { it.startMs },
            beats = beats.sortedBy { it.timeMs },
            sections = sections.sortedBy { it.startMs },
            tempoSegments = tempoSegments.sortedBy { it.startMs },
            key = key,
        )
    }
}
