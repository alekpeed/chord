package com.alekpeed.hearsay.tools.desktop.capture

import com.alekpeed.hearsay.core.model.music.NoteSpelling

/** One key, from the moment it went down to the moment it came up. */
data class PlayedNote(val pitch: Int, val velocity: Int, val onMs: Long, val offMs: Long) {
    val pitchClass: Int get() = Math.floorMod(pitch, 12)
    val octave: Int get() = pitch / 12 - 1
}

/**
 * Everything sounded between the first key going down and the last one coming up.
 *
 * A chord is taken to be one gesture rather than one instant so that rolling or arpeggiating it
 * still counts — a pianist does not strike four keys on the same millisecond, and requiring that
 * would reject correct playing.
 */
data class ChordAttempt(val notes: List<PlayedNote>) {
    val pitchClasses: Set<Int> get() = notes.map { it.pitchClass }.toSet()
    val lowest: PlayedNote? get() = notes.minByOrNull { it.pitch }
    val startMs: Long get() = notes.minOfOrNull { it.onMs } ?: 0
    val endMs: Long get() = notes.maxOfOrNull { it.offMs } ?: 0
}

/** Why an attempt was not what was asked for, in the words that say how to fix it. */
sealed interface Verdict {
    data object Accepted : Verdict
    data class Rejected(val reason: String) : Verdict
}

/**
 * Turns a stream of key presses into chord attempts.
 *
 * A gesture ends when every key is up. The sustain pedal does not suppress key releases, so a
 * pedaled chord still ends where the hands leave it, which is the boundary a player would name.
 */
class GestureDetector {

    private val down = mutableMapOf<Int, Pair<Int, Long>>()
    private val finished = mutableListOf<PlayedNote>()

    fun noteOn(pitch: Int, velocity: Int, timeMs: Long) {
        if (velocity <= 0) {
            noteOff(pitch, timeMs)
            return
        }
        down[pitch] = velocity to timeMs
    }

    /** Returns the completed attempt when this release was the last key up, otherwise null. */
    fun noteOff(pitch: Int, timeMs: Long): ChordAttempt? {
        val started = down.remove(pitch) ?: return null
        finished += PlayedNote(pitch, started.first, started.second, timeMs)
        if (down.isNotEmpty()) return null
        val attempt = ChordAttempt(finished.sortedBy { it.pitch })
        finished.clear()
        return attempt
    }

    fun reset() {
        down.clear()
        finished.clear()
    }

    /** Keys currently held, for showing the player what the app can hear right now. */
    fun held(): Set<Int> = down.keys.toSet()
}

/**
 * Whether an attempt is what the prompt asked for.
 *
 * Pitch content and bass note are exact, because those are the label: a corpus that accepts a
 * nearly-right chord records a wrong one. Register is checked loosely — an octave either side of
 * the request — since the point of asking for a register is coverage, not obedience, and a player
 * fighting the app about which octave to sit in will stop after ten minutes.
 */
object Verifier {

    private const val RegisterToleranceOctaves = 1

    fun verify(item: CaptureItem, attempt: ChordAttempt): Verdict {
        val lowest = attempt.lowest ?: return Verdict.Rejected("Nothing was played.")
        contentProblem(item, attempt)?.let { return Verdict.Rejected(it) }
        bassProblem(item, lowest)?.let { return Verdict.Rejected(it) }
        registerProblem(item, lowest)?.let { return Verdict.Rejected(it) }
        return Verdict.Accepted
    }

    /** Which notes sound. Exact, because this is the label the corpus will be trusted on. */
    private fun contentProblem(item: CaptureItem, attempt: ChordAttempt): String? {
        val expected = item.expectedPitchClasses()
        val missing = expected - attempt.pitchClasses
        val extra = attempt.pitchClasses - expected
        if (missing.isEmpty() && extra.isEmpty()) return null
        return buildString {
            if (missing.isNotEmpty()) append("Missing ${names(missing)}")
            if (missing.isNotEmpty() && extra.isNotEmpty()) append(", ")
            if (extra.isNotEmpty()) append("Extra ${names(extra)}")
            append(".")
        }
    }

    /** Which note is underneath. Exact for the same reason: it is what names the chord. */
    private fun bassProblem(item: CaptureItem, lowest: PlayedNote): String? {
        val required = item.requiredBassPitchClass()
        if (lowest.pitchClass == required) return null
        return "${NoteSpelling.fromPitchClass(required)} has to be the lowest note, " +
            "not ${NoteSpelling.fromPitchClass(lowest.pitchClass)}."
    }

    private fun registerProblem(item: CaptureItem, lowest: PlayedNote): String? =
        when (lowest.octave - item.lowestOctave) {
            in Int.MIN_VALUE..-(RegisterToleranceOctaves + 1) -> "Play it higher."
            in (RegisterToleranceOctaves + 1)..Int.MAX_VALUE -> "Play it lower."
            else -> null
        }

    private fun names(pitchClasses: Set<Int>) =
        pitchClasses.sorted().joinToString(", ") { NoteSpelling.fromPitchClass(it).toString() }
}
