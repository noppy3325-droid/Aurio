package jp.noppy.lumamusic.core.dsp

import kotlin.math.*

fun normalizationGainDb(lufs: Double?, target: Double): Double =
    if (lufs == null || !lufs.isFinite() || !target.isFinite()) 0.0 else (target - lufs).coerceIn(-24.0, 6.0)

/** The audio thread owns the filter state. Settings are supplied at buffer boundaries. */
class PcmDspEngine(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate > 0 && channels > 0)
    }

    private data class Equalizer(val bands: List<Double>, val filters: DspFilters) {
        val hasBoost: Boolean get() = bands.any { it > 0.0 } || filters.lowShelfDb > 0f || filters.highShelfDb > 0f

        fun coefficients(rate: Int): List<Coeff> = buildList {
            if (filters.highPassHz > 0f) add(passCoeff(rate, filters.highPassHz, high = true))
            if (filters.lowPassHz > 0f) add(passCoeff(rate, filters.lowPassHz, high = false))
            if (filters.lowShelfDb != 0f) add(shelfCoeff(rate, DspFilters.LOW_SHELF_HZ, filters.lowShelfDb, high = false))
            if (filters.highShelfDb != 0f) add(shelfCoeff(rate, DspFilters.HIGH_SHELF_HZ, filters.highShelfDb, high = true))
            bands.forEachIndexed { i, gain ->
                if (gain != 0.0) add(peakCoeff(rate, EQ_FREQUENCIES[i], gain))
            }
        }
    }

    private class EqualizerPlan(val equalizer: Equalizer, val coefficients: List<Coeff>, val headroomDb: Double)

    private data class Signal(val plan: EqualizerPlan, val gainDb: Double, val width: Double)

    private class Bank(val signal: Signal, channels: Int, current: Bank?) {
        private val coefficients = signal.plan.coefficients
        val filters: Array<Array<Filter>>?

        init {
            if (current != null && current.signal.plan === signal.plan) {
                filters = current.filters
            } else {
                filters = if (coefficients.isEmpty()) null else Array(channels) {
                    Array(coefficients.size) { i -> Filter(coefficients[i]) }
                }
            }
        }
        private val gain = 10.0.pow(signal.gainDb / 20)
        private val width = signal.width
        private var limitGain = 1.0
        val flatAndUnattenuated: Boolean
            get() = filters == null && gain == 1.0 && width == 1.0 && limitGain == 1.0

        fun render(filtered: DoubleArray, output: DoubleArray, release: Double) {
            // A scalar commutes with these linear filters. Keeping it here preserves filter state
            // when switching gain or width, and all intermediate samples retain Double precision.
            for (ch in filtered.indices) output[ch] = filtered[ch] * gain
            if (width != 1.0) {
                val mid = (output[0] + output[1]) * .5
                val side = (output[0] - output[1]) * .5 * width
                output[0] = mid + side
                output[1] = mid - side
            }
            var peak = 0.0
            for (value in output) peak = max(peak, abs(value))
            if (peak <= 1.0 && limitGain == 1.0) return

            // A shared gain preserves the stereo image. Attack is immediate with an 80 ms release time constant.
            // This is sample-peak protection with zero lookahead.
            val requiredGain = if (peak > 1.0) 1.0 / peak else 1.0
            val releasedGain = limitGain + (1.0 - limitGain) * release
            limitGain = min(requiredGain, if (releasedGain > 1.0 - 1e-8) 1.0 else releasedGain)
            for (ch in output.indices) output[ch] *= limitGain
        }
    }

    private var configured = false
    private var target: Bank? = null
    private var current: Bank? = null
    private var previous: Bank? = null
    private var ramp = 0
    private val rampLength = max(2, sampleRate / 50)
    private val release = -expm1(-1.0 / (.080 * sampleRate))
    private val responseGrid: List<ResponsePoint> by lazy(LazyThreadSafetyMode.NONE) {
        val nyquist = sampleRate / 2.0
        val lower = min(10.0, nyquist)
        val logSpan = log2(nyquist / lower)
        val intervals = max(1, ceil(logSpan * 32).toInt())
        buildList {
            add(ResponsePoint(0.0, sampleRate))
            for (i in 0 until intervals) {
                add(ResponsePoint(lower * 2.0.pow(logSpan * i / intervals), sampleRate))
            }
            add(ResponsePoint(nyquist, sampleRate))
            for (frequency in EQ_FREQUENCIES) {
                if (frequency < nyquist) add(ResponsePoint(frequency, sampleRate))
            }
            if (DspFilters.LOW_SHELF_HZ < nyquist) add(ResponsePoint(DspFilters.LOW_SHELF_HZ.toDouble(), sampleRate))
            if (DspFilters.HIGH_SHELF_HZ < nyquist) add(ResponsePoint(DspFilters.HIGH_SHELF_HZ.toDouble(), sampleRate))
        }
    }

    val isDirect: Boolean get() = current == null && target == null && ramp == 0
    val transitioning: Boolean get() = ramp > 0 || current !== target
    val gainDb: Double get() = target?.signal?.gainDb ?: 0.0

    fun configure(config: DspConfig, analysis: TrackAnalysis? = null) {
        val next = if (config.mode == SoundMode.DIRECT) null else signal(config.safe(), analysis)
        if (next != target?.signal) {
            target = when {
                next == current?.signal -> current
                next == null -> null
                else -> Bank(next, channels, current)
            }
        }
        if (!configured) {
            current = target
            configured = true
        }
    }

    /** Frequency response of the selected filter cascade, excluding scalar gain and peak protection. */
    fun responseDb(frequency: Double): Double {
        if (!frequency.isFinite() || frequency < 0.0 || frequency > sampleRate * .5) return 0.0
        val coefficients = target?.signal?.plan?.coefficients ?: return 0.0
        val point = ResponsePoint(frequency, sampleRate)
        return coefficients.sumOf { 10 * log10(it.responsePower(point).coerceAtLeast(1e-30)) }
    }

    private fun signal(config: DspConfig, analysis: TrackAnalysis?): Signal {
        val bands = List(10) { i ->
            if (EQ_FREQUENCIES[i] >= sampleRate * BIQUAD_RATE_LIMIT) return@List 0.0
            val manual = if (config.eqEnabled) config.bands[i].toDouble() else 0.0
            // Manual EQ wins. Auto only adjusts neutral bands.
            val auto = if (config.autoEnabled && abs(manual) < .5) {
                analysis?.toneGains?.getOrNull(i)?.takeIf { it.isFinite() }?.toDouble()?.coerceIn(-2.0, 2.0) ?: 0.0
            } else 0.0
            (manual + auto).coerceIn(-12.0, 12.0)
        }
        val equalizer = Equalizer(bands, config.filters.effectiveAt(sampleRate))
        val plan = target?.signal?.plan?.takeIf { it.equalizer == equalizer }
            ?: current?.signal?.plan?.takeIf { it.equalizer == equalizer }
            ?: previous?.signal?.plan?.takeIf { it.equalizer == equalizer }
            ?: buildPlan(equalizer)
        val width = if (config.widthEnabled && channels == 2) config.width.toDouble() else 1.0
        val widthHeadroom = 20 * log10(max(1.0, width))
        val normalize = if (config.normalizeEnabled) trackGain(analysis, config.targetLufs.toDouble()) else 0.0
        val desired = config.preampDb + normalize - plan.headroomDb - widthHeadroom
        val peakAllowance = analysis?.peak?.takeIf { it.isFinite() && it > 0.0 }?.let {
            max(0.0, -1 - 20 * log10(it))
        } ?: 0.0
        val gain = min(desired, peakAllowance) + config.comparisonTrimDb
        return Signal(plan, gain, width)
    }

    private fun buildPlan(equalizer: Equalizer): EqualizerPlan {
        val coefficients = equalizer.coefficients(sampleRate)
        var maxPower = 1.0
        if (equalizer.hasBoost) {
            for (point in responseGrid) {
                var power = 1.0
                for (i in coefficients.indices) power *= coefficients[i].responsePower(point)
                if (power > maxPower) maxPower = power
            }
        }
        // Allow one decibel above the sampled steady-state maximum. The limiter below
        // still handles transient peaks and signals between the sampled frequencies.
        val headroom = if (maxPower > 1.0) 1.0 + 10 * log10(maxPower) else 0.0
        return EqualizerPlan(equalizer, coefficients, headroom)
    }

    private fun trackGain(analysis: TrackAnalysis?, targetLufs: Double): Double {
        if (analysis == null) return 0.0
        val measured = analysis.estimatedLufs?.takeIf {
            it.isFinite() && analysis.sampleRate > 0 && analysis.frames >= analysis.sampleRate * .4
        }
        val tagged = analysis.tagGainDb?.takeIf { it.isFinite() }?.coerceIn(-24.0, 6.0)
        if (tagged == null) return normalizationGainDb(measured, targetLufs)
        // Tag-only profiles have no observed PCM. Once PCM is available, avoid boosting a
        // silent track or one shorter than the 400 ms measurement window.
        return if (analysis.sampleRate <= 0 || measured != null) tagged else min(0.0, tagged)
    }

    private val filtered = DoubleArray(channels)
    private val oldFiltered = DoubleArray(channels)
    private val fresh = DoubleArray(channels)
    private val old = DoubleArray(channels)

    private fun applyFilters(
        input: FloatArray,
        offset: Int,
        filters: Array<Array<Filter>>?,
        output: DoubleArray,
    ) {
        for (ch in 0 until channels) {
            val sample = input[offset + ch]
            var value = if (sample.isFinite()) sample.toDouble() else 0.0
            if (filters != null) for (filter in filters[ch]) value = filter.sample(value)
            output[ch] = value
        }
    }

    private fun render(bank: Bank?, filtered: DoubleArray, output: DoubleArray) {
        if (bank != null) {
            bank.render(filtered, output, release)
        } else {
            // During the fade the dry path is mixed with Enhance. Settled Direct skips all processing.
            for (ch in 0 until channels) output[ch] = filtered[ch].coerceIn(-1.0, 1.0)
        }
    }

    fun processFrame(frame: FloatArray) = processFrame(frame, 0)

    private fun processFrame(frame: FloatArray, offset: Int) {
        if (isDirect) return
        if (ramp == 0 && current !== target) {
            previous = current
            current = target
            ramp = rampLength
        }
        val bank = current
        if (ramp == 0 && bank?.flatAndUnattenuated == true) {
            var inRange = true
            for (ch in 0 until channels) {
                val sample = frame[offset + ch]
                inRange = inRange && sample >= -1f && sample <= 1f
            }
            if (inRange) return
        }

        val activeFilters = bank?.filters
        applyFilters(frame, offset, activeFilters, filtered)
        render(bank, filtered, fresh)
        if (ramp > 0) {
            val previousFilters = previous?.filters
            val dryOrFiltered = if (previousFilters === activeFilters) filtered else {
                applyFilters(frame, offset, previousFilters, oldFiltered)
                oldFiltered
            }
            render(previous, dryOrFiltered, old)
            val mix = (rampLength - ramp).toDouble() / (rampLength - 1)
            for (ch in 0 until channels) {
                frame[offset + ch] = (old[ch] * (1.0 - mix) + fresh[ch] * mix).toFloat()
            }
            // If settings arrived during the fade, the latest target starts on the next frame.
            if (--ramp == 0) previous = null
        } else {
            for (ch in 0 until channels) frame[offset + ch] = fresh[ch].toFloat()
        }
    }

    fun process(input: FloatArray): FloatArray {
        val output = input.copyOf()
        if (!isDirect) {
            for (offset in 0..(output.size - channels) step channels) processFrame(output, offset)
        }
        return output
    }
}
