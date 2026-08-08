package com.alekpeed.hearsay.core.model.timeline

import com.alekpeed.hearsay.core.model.music.Chord

/** Where a piece of analysis came from. User corrections never overwrite machine output in place. */
enum class AnalysisSource { MACHINE, USER, SEED, IMPORTED }

/**
 * One harmonic region, held half-open as `[startMs, endMs)` so that the chord landing exactly on a
 * boundary is the one starting there, not the one ending there.
 */
data class ChordEvent(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val chord: Chord?,
    val confidence: Float = 1f,
    val source: AnalysisSource = AnalysisSource.MACHINE,
    val userConfirmed: Boolean = false,
) {
    init {
        require(endMs > startMs) { "Chord event $id ends at $endMs, at or before its start $startMs" }
    }

    val durationMs: Long get() = endMs - startMs

    /** True when this region is explicitly "no chord" rather than an unanalyzed gap. */
    val isNoChord: Boolean get() = chord == null
}

data class BeatEvent(
    val timeMs: Long,
    val beatInMeasure: Int,
    val measureNumber: Int,
    val confidence: Float = 1f,
    val source: AnalysisSource = AnalysisSource.MACHINE,
) {
    val isDownbeat: Boolean get() = beatInMeasure == 1
}

data class TempoSegment(
    val startMs: Long,
    val endMs: Long,
    val bpm: Float,
    val confidence: Float = 1f,
)

data class SectionEvent(
    val id: String,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val orderIndex: Int,
    val confidence: Float = 1f,
    val source: AnalysisSource = AnalysisSource.MACHINE,
)

/** A contiguous stretch of the song a player has isolated to work on. */
data class LoopRange(val startMs: Long, val endMs: Long) {
    init {
        require(endMs > startMs) { "Loop ends at $endMs, at or before its start $startMs" }
    }

    fun contains(timeMs: Long): Boolean = timeMs in startMs until endMs
}
