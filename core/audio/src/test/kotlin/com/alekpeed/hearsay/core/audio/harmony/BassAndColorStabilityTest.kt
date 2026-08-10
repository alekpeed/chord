package com.alekpeed.hearsay.core.audio.harmony

import com.alekpeed.hearsay.core.audio.feature.Chromagram
import com.alekpeed.hearsay.core.model.music.Alteration
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.SeventhType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BassAndColorStabilityTest {

    @Test
    @Suppress("CyclomaticComplexMethod") // Assertions intentionally cover every historical failure mode.
    fun `strong rapid bass is separate from identity while a real upper change survives`() {
        val durationsMs = listOf(300L, 450L, 700L, 350L, 600L, 500L, 400L, 650L)
        val bassLine = listOf(9, 8, 7, 5, 4, 2, 0, 5) // A, Ab, G, F, E, D, C, F
        val boundaries = durationsMs.runningFold(0L, Long::plus)
        val hopSeconds = 0.05
        val frameCount = (boundaries.last() / (hopSeconds * 1_000)).toInt() + 1

        // Six short spans of C7 followed by a genuine upper-harmony move to F7. Bass is much
        // stronger than the upper shell and visits roots of the historical false readings.
        val fullBand = Array(frameCount) { frame ->
            val timeMs = (frame * hopSeconds * 1_000).toLong()
            val span = boundaries.indexOfLast { it <= timeMs }.coerceAtMost(bassLine.lastIndex)
            val upper = if (span < 6) listOf(0, 4, 7, 10) else listOf(5, 9, 0, 3)
            FloatArray(12).also { values ->
                for (pc in upper) values[pc] += 0.45f
                values[bassLine[span]] += 3.0f
            }.let(Chromagram::normalize)
        }
        val lowBand = Array(frameCount) { frame ->
            val timeMs = (frame * hopSeconds * 1_000).toLong()
            val span = boundaries.indexOfLast { it <= timeMs }.coerceAtMost(bassLine.lastIndex)
            FloatArray(12).also { it[bassLine[span]] = 1f }
        }

        val recognized = ChordRecognizer(slashChords = true, extensionPenalty = 1f).recognize(
            chroma = Chromagram(fullBand, hopSeconds),
            beatTimesMs = boundaries,
            bassChroma = Chromagram(lowBand, hopSeconds),
            key = KeyContext(tonicPitchClass = 0, isMinor = false, confidence = 0.8f),
            changeLikelihood = FloatArray(durationsMs.size) { index -> if (index == 6) 1f else 0.9f },
        )

        assertEquals("Bass reporting must preserve the moving line", bassLine, recognized.map { it.bassPitchClass })
        val stable = recognized.take(6).map { it.chord ?: error("Expected C7 identity") }
        assertTrue("Bass motion changed C7 identity: $stable", stable.all { chord ->
            chord.root.pitchClass == 0 &&
                chord.quality == ChordQuality.MAJOR &&
                chord.seventh == SeventhType.MINOR
        })
        assertTrue("Walking bass was promoted to upper color: $stable", stable.all { chord ->
            chord.extensions.isEmpty() && chord.alterations.isEmpty()
        })

        val afterUpperChange = recognized.drop(6).mapNotNull { it.chord }
        assertTrue("A corroborated F7 change must remain detectable: $afterUpperChange", afterUpperChange.any { chord ->
            chord.root.pitchClass == 5 &&
                chord.quality == ChordQuality.MAJOR &&
                chord.seventh == SeventhType.MINOR
        })
        assertTrue(
            "Historical false alternatives returned: ${recognized.map { it.chord }}",
            recognized.none { reading ->
                val chord = reading.chord ?: return@none false
                (chord.root.pitchClass == 9 && chord.quality == ChordQuality.MINOR) || // Am
                    (chord.root.pitchClass == 5 && chord.quality == ChordQuality.MINOR) || // Fm
                    (chord.root.pitchClass == 2 && chord.quality == ChordQuality.MINOR) || // Dm7b9
                    (chord.root.pitchClass == 0 && chord.seventh == SeventhType.MAJOR) // Cmaj13b9
            },
        )
    }

    @Test
    fun `walking bass does not rewrite a stable dominant harmony`() {
        val bassLine = listOf(0, 9, 8, 7, 5, 4, 2, 0)
        val framesPerSpan = 10

        // The upper harmony stays C7. Each span also contains a deliberately strong moving bass
        // pitch so this fixture reproduces the failure mode where the bass used to pull the root
        // toward Am, Ab, G, F, Em, Dm, etc.
        val harmonicFrames = Array(bassLine.size * framesPerSpan + 1) { frame ->
            val bass = bassLine[minOf(frame / framesPerSpan, bassLine.lastIndex)]
            val values = FloatArray(12)
            for (pc in listOf(0, 4, 7, 10)) values[pc] += 0.80f
            values[bass] += 1.20f
            Chromagram.normalize(values)
        }
        val bassFrames = Array(harmonicFrames.size) { frame ->
            val bass = bassLine[minOf(frame / framesPerSpan, bassLine.lastIndex)]
            FloatArray(12).also { it[bass] = 1f }
        }

        val recognized = ChordRecognizer(
            slashChords = true,
            extensionPenalty = 1f,
        ).recognize(
            chroma = Chromagram(harmonicFrames, hopSeconds = 0.1),
            beatTimesMs = (0..bassLine.size).map { it * 1_000L },
            bassChroma = Chromagram(bassFrames, hopSeconds = 0.1),
            key = KeyContext(tonicPitchClass = 0, isMinor = false, confidence = 1f),
            // Simulate a boundary detector being over-excited by every bass note. The decoder still
            // must not reinterpret the whole harmony without corroborating upper-harmony movement.
            changeLikelihood = FloatArray(bassLine.size) { if (it == 0) 0f else 1f },
        )

        val chords = recognized.mapNotNull { it.chord }
        assertTrue("Expected recognized harmony", chords.isNotEmpty())
        assertTrue(
            "Moving bass rewrote the chord roots: ${chords.map { it.root.pitchClass }}",
            chords.all { it.root.pitchClass == 0 },
        )
        assertTrue(
            "Bass tracking should still preserve the moving line",
            recognized.mapNotNull { it.bassPitchClass }.distinct().size >= 5,
        )
    }

    @Test
    fun `full detail keeps stable altered color without creating another root`() {
        val values = FloatArray(12)
        // C7 shell.
        for (pc in listOf(0, 4, 7, 10)) values[pc] = 1.0f
        // Stable b9, #11 and 13 color.
        values[1] = 0.68f
        values[6] = 0.66f
        values[9] = 0.64f
        val frame = Chromagram.normalize(values)
        val chroma = Chromagram(Array(21) { frame.copyOf() }, hopSeconds = 0.1)

        val recognized = ChordRecognizer(
            slashChords = false,
            extensionPenalty = 1f,
        ).recognize(
            chroma = chroma,
            beatTimesMs = listOf(0L, 2_000L),
            key = KeyContext(tonicPitchClass = 0, isMinor = false, confidence = 1f),
        )

        val chord = recognized.single().chord ?: error("Expected a chord")
        assertEquals("Color should not change the harmonic root", 0, chord.root.pitchClass)
        assertTrue("Expected b9 in $chord", Alteration.FLAT_NINE in chord.alterations)
        assertTrue("Expected #11 in $chord", Alteration.SHARP_ELEVEN in chord.alterations)
        assertTrue("Expected 13 in $chord", 13 in chord.extensions)
    }
}
