package com.alekpeed.hearsay.core.audio.rhythm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The downbeat and meter estimates must agree about what counts as evidence.
 *
 * Each test here is a regression against a way the two could disagree: the meter scoring accents
 * on a different blend than the one that placed the bar lines, a phase winning on member count
 * rather than on evidence, and a chord-change stream made of pure chroma jitter being normalized
 * up into a vote. The fixtures use beat index as frame index so each beat reads its own onset.
 */
class DownbeatEstimatorTest {

    private fun beatFrames(count: Int): List<Int> = List(count) { it }

    private fun envelope(onsets: FloatArray): OnsetEnvelope = OnsetEnvelope(onsets, 0.05)

    @Test
    fun `a backbeat groove in four is reported in four`() {
        // Kick on the even beats, louder snare on the odd — the accent pattern of most pop — with
        // the harmony moving every four beats. The phase for each candidate is chosen by the
        // normalized, harmony-dominated estimate, so candidate four's bar lines land on the quiet
        // kicks; when the contrast was still scored on the raw mix, that collapsed candidate
        // four's score and the meter came back as six or three.
        val onsets = FloatArray(16) { if (it % 2 == 0) 1f else 3f }
        val changes = FloatArray(16) { if (it % 4 == 0) 0.9f else 0.02f }

        val meter = DownbeatEstimator.estimateBeatsPerMeasure(beatFrames(16), envelope(onsets), changes)

        assertEquals(4, meter)
    }

    @Test
    fun `a phase with fewer beats can still win on evidence`() {
        // Ten beats grouped in four: phases zero and one hold three beats, phases two and three
        // only two. The single accented beat — louder onset and a slightly stronger chord change —
        // sits on phase three. Comparing phase sums handed phase zero the win on its extra member
        // alone; per-member means let the evidence decide.
        val onsets = FloatArray(10) { if (it == 3) 1.5f else 1f }
        val changes = FloatArray(10) { if (it == 3) 0.12f else 0.1f }

        val phase = DownbeatEstimator.estimate(beatFrames(10), envelope(onsets), changes, 4)

        assertEquals(3, phase)
    }

    @Test
    fun `a chord-change stream of pure jitter does not outvote real accents`() {
        // Every downbeat carries a consistent accent; the change stream never leaves the chroma
        // wobble of a held chord (mean well under the evidence floor) apart from one slightly
        // larger blip on a different phase. The accent is kept modest on purpose: normalizing the
        // jitter to a mean of one and weighting it three to one was enough for the blip to move
        // the downbeat, which is exactly the amplification the floor exists to refuse.
        val onsets = FloatArray(16) { if (it % 4 == 0) 1.2f else 1f }
        val changes = FloatArray(16) { if (it == 5) 0.004f else 0.003f }

        val phase = DownbeatEstimator.estimate(beatFrames(16), envelope(onsets), changes, 4)

        assertEquals(0, phase)
    }
}
