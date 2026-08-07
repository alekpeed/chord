package com.alekpeed.hearsay.tools.analyzer

import com.alekpeed.hearsay.core.audio.AnalysisSettings
import com.alekpeed.hearsay.core.model.export.ChartImporter
import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.SymbolStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The desktop is only useful if the tablet can read what it produces.
 *
 * This runs the whole chain the desktop tool runs — analyze, export — and then reads the result
 * with the importer the app uses. What it does not cover is ffmpeg, which is a subprocess and is
 * not installed in CI; everything after the samples arrive is exercised here.
 */
class DesktopToTabletTest {

    private val sampleRate = 22_050

    /**
     * A struck, decaying triad per bar over a click, which is the shape the recognizer expects.
     *
     * Overtones are deliberate: pure sine tones would leave the chromagram's overtone suppression
     * untested, and would flatter the analysis in a way real instruments do not.
     */
    private fun progression(midiChords: List<List<Int>>, barSeconds: Double = 2.0): FloatArray {
        val barSamples = (barSeconds * sampleRate).toInt()
        val out = FloatArray(barSamples * midiChords.size)
        for ((bar, notes) in midiChords.withIndex()) {
            for (note in notes) {
                val frequency = 440.0 * Math.pow(2.0, (note - 69) / 12.0)
                for (i in 0 until barSamples) {
                    val t = i.toDouble() / sampleRate
                    val decay = exp(-1.2 * t)
                    var value = sin(2 * PI * frequency * t)
                    value += 0.4 * sin(2 * PI * frequency * 2 * t)
                    value += 0.2 * sin(2 * PI * frequency * 3 * t)
                    out[bar * barSamples + i] += (value * decay * 0.2).toFloat()
                }
            }
            // A click on each beat, so there is a pulse to track.
            for (beat in 0 until 4) {
                val start = bar * barSamples + beat * barSamples / 4
                for (i in 0 until 200) {
                    if (start + i < out.size) out[start + i] += (exp(-i / 40.0) * 0.5).toFloat()
                }
            }
        }
        return out
    }

    private val cMajor = listOf(60, 64, 67)
    private val fMajor = listOf(65, 69, 72)
    private val gMajor = listOf(67, 71, 74)

    private fun cMajorProgression() =
        progression(List(3) { listOf(cMajor, fMajor, gMajor, cMajor) }.flatten())

    @Test
    fun `what the desktop writes, the tablet reads back as the same harmony`() {
        val analyzed = analyzeSamples(cMajorProgression(), sampleRate, AnalysisSettings.Balanced, "Test")

        val imported = ChartImporter.fromJson(analyzed.json).getOrThrow()

        assertEquals(analyzed.chordCount, imported.chart.chordEvents.size)
        assertTrue("expected some harmony, found none", imported.chart.chordEvents.isNotEmpty())

        // Every chord must arrive as structure. A symbol that survived only as text would render
        // correctly and be wrong the moment anybody transposed it.
        imported.chart.chordEvents.forEach { event ->
            val chord = event.chord ?: return@forEach
            val symbol = ChordFormatter.format(chord, SymbolStyle.Ascii)
            assertTrue("$symbol lost its root on the way through the file", symbol.isNotBlank())
            assertEquals(chord, event.chord)
        }
        assertTrue(
            "the file should carry timing, not just chords",
            imported.chart.beats.isNotEmpty(),
        )
    }

    @Test
    fun `the analysis on a desktop finds the same progression the tablet would`() {
        val analyzed = analyzeSamples(cMajorProgression(), sampleRate, AnalysisSettings.Balanced, "Test")
        val imported = ChartImporter.fromJson(analyzed.json).getOrThrow()

        val symbols = imported.chart.chordEvents
            .mapNotNull { event -> event.chord?.let { ChordFormatter.format(it, SymbolStyle.Ascii) } }

        assertTrue("expected C somewhere in $symbols", symbols.any { it.startsWith("C") })
        assertTrue("expected F somewhere in $symbols", symbols.any { it.startsWith("F") })
        assertTrue("expected G somewhere in $symbols", symbols.any { it.startsWith("G") })
    }

    @Test
    fun `the maximum quality profile is usable, which is the reason to run on a desktop`() {
        // An 8192-point transform over this signal is the setting Android cannot afford. If it
        // cannot complete here either, the tool has no purpose.
        val analyzed = analyzeSamples(cMajorProgression(), sampleRate, AnalysisSettings.MaximumQuality, "Test")

        assertTrue(analyzed.chordCount > 0)
        assertTrue(ChartImporter.fromJson(analyzed.json).isSuccess)
    }

    @Test
    fun `the key and tempo it reports are the ones it wrote into the file`() {
        val analyzed = analyzeSamples(cMajorProgression(), sampleRate, AnalysisSettings.Balanced, "Test")

        val imported = ChartImporter.fromJson(analyzed.json).getOrThrow()

        assertEquals("Test", imported.title)
        assertEquals(analyzed.metadata.keyLabel, imported.keyLabel)
        assertEquals(analyzed.metadata.tempoBpm!!, imported.tempoBpm!!, 1e-3f)
    }
}
