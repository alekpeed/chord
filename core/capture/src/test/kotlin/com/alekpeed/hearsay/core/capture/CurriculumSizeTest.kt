package com.alekpeed.hearsay.core.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How much playing the curriculum asks for.
 *
 * Pinned because the size is a promise to whoever sits down at the piano: at roughly four seconds
 * a chord this is about an hour, and a change that quietly doubles it turns a session somebody
 * will finish into one they will not.
 */
class CurriculumSizeTest {

    @Test
    fun `the whole curriculum is about an hour of playing`() {
        val all = Curriculum.all()
        assertEquals(844, all.size)

        val seconds = all.size * 4
        assertEquals("about an hour", 56, seconds / 60)
    }

    @Test
    fun `each block is the size it claims to be`() {
        val counts = Curriculum.all().groupingBy { it.block }.eachCount()
        assertEquals(168, counts[Block.CORE])
        assertEquals(192, counts[Block.INVERSIONS])
        assertEquals(36, counts[Block.REGISTER])
        assertEquals(180, counts[Block.VOICINGS])
        assertEquals(144, counts[Block.EXTENSIONS])
        assertEquals(40, counts[Block.AMBIGUOUS])
        assertEquals(48, counts[Block.PEDAL])
        assertEquals(36, counts[Block.MELODY])
    }

    @Test
    fun `the bass-critical blocks are the largest, because that is where the analyzer is weakest`() {
        val counts = Curriculum.all().groupingBy { it.block }.eachCount()
        val bassCritical = (counts[Block.INVERSIONS] ?: 0) + (counts[Block.VOICINGS] ?: 0) +
            (counts[Block.REGISTER] ?: 0) + (counts[Block.AMBIGUOUS] ?: 0)
        assertEquals(true, bassCritical > Curriculum.all().size / 2)
    }
}
