package jp.noppy.lumamusic.core.dsp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class PcmFilterTest {
    @Test fun butterworthCutoffsMatchPcmAmplitudeAndPhase() {
        for (rate in listOf(44100, 48000, 96000)) {
            verifyTone(rate, DspFilters(highPassHz = 80f), 80.0, -3.0103, 90.0)
            verifyTone(rate, DspFilters(lowPassHz = 8000f), 8000.0, -3.0103, -90.0)
        }
    }

    @Test fun shelvesReachHalfGainAtTheirMidpoints() {
        for (rate in listOf(44100, 48000)) {
            verifyTone(rate, DspFilters(lowShelfDb = 6f), 120.0, 3.0)
            verifyTone(rate, DspFilters(lowShelfDb = -6f), 120.0, -3.0)
            verifyTone(rate, DspFilters(highShelfDb = 6f), 6000.0, 3.0)
            verifyTone(rate, DspFilters(highShelfDb = -6f), 6000.0, -3.0)
        }
    }

    @Test fun graphMatchesTheCombinedPcmResponse() {
        val config = DspConfig(
            mode = SoundMode.ENHANCE,
            filters = DspFilters(80f, 9000f, -4f, 6f),
            bands = List(10) { if (it == 5) 3f else 0f },
        )
        for (frequency in listOf(40.0, 120.0, 1000.0, 6000.0, 12000.0)) {
            val engine = PcmDspEngine(48000, 1)
            engine.configure(config)
            val (amplitudeDb) = measureTone(engine, frequency)
            assertEquals(engine.responseDb(frequency), amplitudeDb - engine.gainDb, .025)
        }
    }

    @Test fun broadBoostLeavesUsefulOutputAndBoundsFullScalePcm() {
        val engine = PcmDspEngine(48000, 1)
        engine.configure(DspConfig(mode = SoundMode.ENHANCE, bands = List(10) { 12f }))

        val (amplitudeDb) = measureTone(engine, 1000.0)
        assertTrue("1 kHz output should remain within 6 dB of the input level: $amplitudeDb dB", amplitudeDb > -6.0)
        assertTrue("Apply headroom even with this many overlapping boosts", engine.gainDb < -12.0)

        val fullScale = FloatArray(48000) { frame ->
            if (frame % 500 < 100) 1f else -1f
        }
        val output = engine.process(fullScale)
        assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(output.any { abs(it) > .1f })
    }

    @Test fun ratesNearNyquistClampLowPassAndBypassUnsupportedShelves() {
        val filters = DspFilters(lowPassHz = 20000f, highShelfDb = 9f)
        val effective = filters.effectiveAt(8000)
        assertEquals(3840f, effective.lowPassHz, 0f)
        assertEquals(0f, effective.highShelfDb, 0f)
        verifyTone(8000, filters, 3840.0, -3.0103, -90.0)
        val engine = PcmDspEngine(6000, 1)
        engine.configure(DspConfig(mode = SoundMode.ENHANCE, filters = filters))
        assertEquals(0.0, engine.responseDb(2900.0), 0.0)
        assertEquals(0.0, engine.gainDb, 0.0)
    }

    @Test fun comparisonTrimAttenuatesEnhanceWithoutChangingFrequencyResponse() {
        val engine = PcmDspEngine(48000, 1)
        val config = DspConfig(mode = SoundMode.ENHANCE, comparisonTrimDb = -6f)
        engine.configure(config)
        assertEquals(-6.0, engine.gainDb, 0.0)
        assertEquals(0.0, engine.responseDb(1000.0), 0.0)
        assertEquals(.5011872f, engine.process(floatArrayOf(1f)).single(), 1e-6f)

        val direct = PcmDspEngine(48000, 1)
        direct.configure(config.copy(mode = SoundMode.DIRECT, filters = DspFilters(80f, 9000f, -4f, 6f)))
        assertEquals(0.0, direct.gainDb, 0.0)
        assertEquals(0.0, direct.responseDb(40.0), 0.0)
        val input = floatArrayOf(-1f, -0.0f, Float.fromBits(0x7fc12345), 1f)
        assertArrayEquals(input.map(Float::toRawBits).toIntArray(), direct.process(input).map(Float::toRawBits).toIntArray())
    }

    @Test fun rapidChangesKeepAnInFlightFadeAndApplyTheLatestSettings() {
        val start = DspConfig(mode = SoundMode.ENHANCE, filters = DspFilters(highPassHz = 50f))
        val first = start.copy(filters = DspFilters(lowPassHz = 3200f))
        val last = start.copy(filters = DspFilters(lowShelfDb = -6f), comparisonTrimDb = -4f)
        val actual = PcmDspEngine(48000, 1)
        val expected = PcmDspEngine(48000, 1)
        for (engine in listOf(actual, expected)) {
            engine.configure(start)
            engine.process(FloatArray(4800) { .15f })
            engine.configure(first)
            engine.process(FloatArray(240) { .15f })
        }
        actual.configure(start.copy(filters = DspFilters(highShelfDb = 8f)))
        actual.configure(DspConfig(mode = SoundMode.DIRECT))
        actual.configure(last)

        val remaining = FloatArray(720) { .15f }
        val finishingFirstFade = actual.process(remaining)
        assertArrayEquals(expected.process(remaining), finishingFirstFade, 0f)
        expected.configure(last)
        val after = actual.process(FloatArray(2048) { .15f })
        assertArrayEquals(expected.process(FloatArray(2048) { .15f }), after, 0f)
        assertEquals(finishingFirstFade.last(), after.first(), 1e-6f)
        assertFalse(actual.transitioning)
        assertEquals(.15 * 10.0.pow(-10.0 / 20), after.last().toDouble(), .001)
    }

    @Test fun extremeRatesAndChannelCountsStayFiniteAndKeepTheirChannels() {
        val settings = DspConfig(
            mode = SoundMode.ENHANCE,
            bands = List(10) { if (it % 2 == 0) 12f else -12f },
            preampDb = 6f,
            filters = DspFilters(20f, 20000f, 9f, -9f),
        )
        for (rate in listOf(8000, 44100, 48000, 96000, 192000)) {
            for (channels in listOf(1, 2, 8)) {
                val engine = PcmDspEngine(rate, channels)
                engine.configure(settings)
                val block = FloatArray(2 * rate * channels)
                for (frame in 0 until 256) {
                    block[frame * channels] = when (frame % 4) {
                        0 -> 1f
                        1 -> -1f
                        2 -> Float.NaN
                        else -> Float.POSITIVE_INFINITY
                    }
                }
                val output = engine.process(block)
                assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
                assertTrue(output.indices.all { it % channels == 0 || output[it] == 0f })
                assertTrue(output.takeLast(channels * 16).all { abs(it) < 1e-6f })
            }
        }
    }

    @Test fun flatEnhancePreservesFullScaleSamplesFrom16And24BitPcm() {
        for (bits in listOf(16, 24)) {
            val scale = (1 shl (bits - 1)).toFloat()
            val input = floatArrayOf(-1f, (scale - 1f) / scale, -0.0f, 1f, 1f / scale, -1f / scale)
            val engine = PcmDspEngine(44100, 2)
            engine.configure(DspConfig(mode = SoundMode.ENHANCE))
            assertArrayEquals(input.map(Float::toRawBits).toIntArray(), engine.process(input).map(Float::toRawBits).toIntArray())
        }
    }

    private fun verifyTone(
        rate: Int,
        filters: DspFilters,
        frequency: Double,
        expectedDb: Double,
        expectedPhase: Double? = null,
    ) {
        val engine = PcmDspEngine(rate, 1)
        engine.configure(DspConfig(mode = SoundMode.ENHANCE, filters = filters))
        assertEquals(expectedDb, engine.responseDb(frequency), .025)
        val (amplitudeDb, phase) = measureTone(engine, frequency)
        assertEquals(expectedDb, amplitudeDb - engine.gainDb, .025)
        if (expectedPhase != null) assertEquals(expectedPhase, phase, .25)
    }

    private fun measureTone(engine: PcmDspEngine, frequency: Double): Pair<Double, Double> {
        val rate = engine.sampleRate
        val omega = 2 * PI * frequency / rate
        val amplitude = .1
        val output = engine.process(FloatArray(rate) { (amplitude * sin(omega * it)).toFloat() })
        var inPhase = 0.0
        var quadrature = 0.0
        for (frame in rate / 2 until rate) {
            inPhase += output[frame] * sin(omega * frame)
            quadrature += output[frame] * cos(omega * frame)
        }
        val ratio = hypot(inPhase, quadrature) * 4 / (rate * amplitude)
        return (20 * log10(ratio)) to (atan2(quadrature, inPhase) * 180 / PI)
    }
}
