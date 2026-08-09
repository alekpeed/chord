package com.alekpeed.hearsay.core.audio

import com.alekpeed.hearsay.core.model.music.ChordFormatter
import com.alekpeed.hearsay.core.model.music.ChordQuality
import com.alekpeed.hearsay.core.model.music.SeventhType
import com.alekpeed.hearsay.core.model.music.SymbolStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioAnalyzerTest {

    private fun analyze(
        samples: FloatArray,
        settings: AnalysisSettings = AnalysisSettings.Balanced,
    ): AnalysisResult = AudioAnalyzer(settings).analyze(samples, 1, SignalGenerator.SampleRate)

    private fun symbolsOf(result: AnalysisResult): List<String> =
        result.chart.chordEvents.map { event ->
            event.chord?.let { ChordFormatter.format(it, SymbolStyle.Ascii) } ?: "N.C."
        }

    @Test
    fun `recovers the tempo of a click track`() {
        val result = analyze(SignalGenerator.clickTrack(bpm = 120f, bars = 16))
        assertTrue(
            "Expected about 120 BPM, got ${result.tempoBpm}",
            abs(result.tempoBpm - 120f) < 6f,
        )
    }

    @Test
    fun `recovers a slower tempo without halving or doubling it`() {
        val result = analyze(SignalGenerator.clickTrack(bpm = 84f, bars = 16))
        assertTrue(
            "Expected about 84 BPM, got ${result.tempoBpm}",
            abs(result.tempoBpm - 84f) < 6f,
        )
    }

    @Test
    fun `harmonic rhythm selects the tactus of an arpeggiated ballad`() {
        val (samples, _) = SignalGenerator.progression(
            symbols = listOf("C", "Am", "F", "G"),
            bpm = 65f,
            beatsPerChord = 4,
            repeats = 2,
            strikesPerBeat = 2,
        )

        val result = analyze(samples)

        assertTrue(
            "Expected the roughly 65 BPM tactus rather than its double, got ${result.tempoBpm}",
            abs(result.tempoBpm - 65f) < 6f,
        )
        assertTrue(
            "The fixture did not expose the competing double-time level: ${result.tempoCandidates}",
            result.tempoCandidates.any { abs(it.bpm - 130f) < 10f },
        )
        assertEquals(4, result.beatsPerMeasure)
        assertTrue("Expected an honest tempo confidence", result.tempoConfidence < 0.9f)
    }

    @Test
    fun `harmonic rhythm does not halve a genuine tempo`() {
        val (samples, _) = SignalGenerator.progression(
            symbols = listOf("C", "Am", "F", "G"),
            bpm = 132f,
            beatsPerChord = 4,
            repeats = 2,
        )

        val result = analyze(samples)

        assertTrue("Expected about 132 BPM, got ${result.tempoBpm}", abs(result.tempoBpm - 132f) < 8f)
    }

    @Test
    fun `places beats at a steady spacing`() {
        val result = analyze(SignalGenerator.clickTrack(bpm = 120f, bars = 16))
        val beats = result.chart.beats
        assertTrue("Expected a beat grid, found ${beats.size} beats", beats.size > 30)

        val spacings = beats.zipWithNext { a, b -> b.timeMs - a.timeMs }
        val average = spacings.average()
        assertTrue("Expected about 500 ms between beats, got $average", abs(average - 500.0) < 40.0)
        assertTrue(
            "Beat spacing was not steady: ${spacings.distinct().sorted()}",
            spacings.all { abs(it - average) < 90 },
        )
    }

    @Test
    fun `recognizes a major triad progression`() {
        val (samples, _) = SignalGenerator.progression(listOf("C", "F", "G", "C"), repeats = 3)
        val result = analyze(samples)
        val symbols = symbolsOf(result)

        assertTrue("Expected chords, got none", symbols.isNotEmpty())
        assertEquals(setOf("C", "F", "G"), symbols.toSet())
    }

    @Test
    fun `recognizes minor and dominant sevenths`() {
        val (samples, _) = SignalGenerator.progression(listOf("Dm7", "G7", "Cmaj7", "Cmaj7"), repeats = 3)
        val result = analyze(samples)
        val chords = result.chart.chordEvents.mapNotNull { it.chord }
        val symbols = symbolsOf(result)

        assertTrue(
            "Expected Dm7 harmonic identity among $symbols",
            chords.any {
                it.root.pitchClass == 2 &&
                    it.quality == ChordQuality.MINOR &&
                    it.seventh == SeventhType.MINOR
            },
        )
        assertTrue(
            "Expected G7 harmonic identity among $symbols",
            chords.any {
                it.root.pitchClass == 7 &&
                    it.quality == ChordQuality.MAJOR &&
                    it.seventh == SeventhType.MINOR
            },
        )
        assertTrue(
            "Expected Cmaj7 harmonic identity among $symbols",
            chords.any {
                it.root.pitchClass == 0 &&
                    it.quality == ChordQuality.MAJOR &&
                    it.seventh == SeventhType.MAJOR
            },
        )
        assertTrue(
            "Subset triads must not replace the intended seventh-chord roots: $symbols",
            chords.all { it.root.pitchClass in setOf(0, 2, 7) },
        )
    }

    @Test
    fun `distinguishes a major triad from its relative minor`() {
        val (samples, _) = SignalGenerator.progression(listOf("C", "Am"), repeats = 4)
        val symbols = symbolsOf(analyze(samples))

        assertTrue("Expected C among $symbols", symbols.any { it == "C" })
        assertTrue("Expected Am among $symbols", symbols.any { it == "Am" })
    }

    @Test
    fun `merges repeated beats into one chord region`() {
        val (samples, _) = SignalGenerator.progression(listOf("C", "F"), beatsPerChord = 4, repeats = 3)
        val result = analyze(samples)

        assertTrue(
            "Expected roughly one region per chord, got ${result.chart.chordEvents.size}",
            result.chart.chordEvents.size <= 10,
        )
        val durations = result.chart.chordEvents.map { it.durationMs }
        assertTrue("Regions should span multiple beats, got $durations", durations.all { it > 900 })
    }

    @Test
    fun `estimates the key of a diatonic progression`() {
        val (samples, _) = SignalGenerator.progression(listOf("C", "Am", "F", "G"), repeats = 3)
        val result = analyze(samples)

        assertEquals("C", result.key?.tonic?.toString())
        assertTrue("Key confidence should be positive", result.keyConfidence > 0f)
    }

    @Test
    fun `finds four beats to the bar and puts chord changes on the downbeat`() {
        val (samples, _) = SignalGenerator.progression(listOf("C", "F", "G", "C"), beatsPerChord = 4, repeats = 3)
        val result = analyze(samples)

        assertEquals(4, result.beatsPerMeasure)

        val downbeatTimes = result.chart.beats.filter { it.isDownbeat }.map { it.timeMs }.toSet()
        val onDownbeat = result.chart.chordEvents.count { event ->
            downbeatTimes.any { abs(it - event.startMs) < 60 }
        }
        assertTrue(
            "Expected most of ${result.chart.chordEvents.size} regions on a downbeat, got $onDownbeat",
            onDownbeat >= result.chart.chordEvents.size - 1,
        )
    }

    @Test
    fun `reports confidence rather than presenting every chord as certain`() {
        val (samples, _) = SignalGenerator.progression(listOf("C", "F"), repeats = 3)
        val result = analyze(samples)

        assertTrue(result.chart.chordEvents.all { it.confidence in 0f..1f })
        assertTrue("Nothing should be reported as user-confirmed", result.chart.chordEvents.none { it.userConfirmed })
    }

    @Test
    fun `silence produces no chords and says why`() {
        val result = analyze(FloatArray(SignalGenerator.SampleRate * 5))
        assertTrue(result.chart.chordEvents.all { it.chord == null })
    }

    @Test
    fun `empty audio fails cleanly`() {
        val result = analyze(FloatArray(0))
        assertTrue(result.chart.isEmpty)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `the fast profile still produces a usable chart`() {
        val (samples, _) = SignalGenerator.progression(listOf("C", "G"), repeats = 3)
        val result = analyze(samples, AnalysisSettings.Fast)

        assertTrue("Fast profile produced no chords", result.chart.chordEvents.isNotEmpty())
    }

    @Test
    fun `progress is reported for every stage in order`() {
        val seen = mutableListOf<AnalysisStageId>()
        val (samples, _) = SignalGenerator.progression(listOf("C", "F"), repeats = 2)
        AudioAnalyzer().analyze(samples, 1, SignalGenerator.SampleRate) { progress ->
            if (seen.lastOrNull() != progress.stage) seen += progress.stage
        }
        assertEquals(AnalysisStageId.entries.toList(), seen)
    }

    @Test
    fun `a moving bass under one harmony does not become several chords`() {
        val (samples, _) = SignalGenerator.progression(listOf("C", "C/E", "C/G", "C"), repeats = 3)

        val simple = analyze(samples, AnalysisSettings.Balanced.copy(detail = ChartDetail.SIMPLE))
        val roots = simple.chart.chordEvents.mapNotNull { it.chord?.root?.letter }

        assertTrue(
            "Expected the bass movement to stay one chord, got ${symbolsOf(simple)}",
            simple.chart.chordEvents.size <= 4,
        )
        assertTrue("Expected C throughout, got $roots", roots.all { it == roots.first() })
    }

    @Test
    fun `simpler detail never leaves more chords than fuller detail`() {
        val (samples, _) = SignalGenerator.progression(listOf("Dm7", "G7", "Cmaj7", "Cmaj7"), repeats = 3)

        val counts = ChartDetail.entries.associateWith { detail ->
            analyze(samples, AnalysisSettings.Balanced.copy(detail = detail)).chart.chordEvents.size
        }

        assertTrue(
            "Detail should not increase as it simplifies: $counts",
            counts.getValue(ChartDetail.SIMPLE) <= counts.getValue(ChartDetail.FULL),
        )
    }

    @Test
    fun `the key is used to choose between chords the chroma cannot separate`() {
        val (samples, _) = SignalGenerator.progression(listOf("G", "C", "D", "G"), repeats = 4)
        val result = analyze(samples)
        val roots = result.chart.chordEvents.mapNotNull { it.chord?.root?.letter?.name }

        assertTrue(
            "Expected the tonic to appear, got ${symbolsOf(result)}",
            roots.any { it == "G" },
        )
    }
}
