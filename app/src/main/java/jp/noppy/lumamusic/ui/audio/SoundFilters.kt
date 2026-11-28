package jp.noppy.lumamusic.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import jp.noppy.lumamusic.R
import jp.noppy.lumamusic.core.dsp.DspConfig
import jp.noppy.lumamusic.core.dsp.DspFilters
import jp.noppy.lumamusic.core.dsp.PcmDspEngine
import jp.noppy.lumamusic.core.dsp.TrackAnalysis
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
internal fun SoundFilterControls(
    filters: DspFilters,
    sampleRate: Int?,
    enabled: Boolean,
    onChange: (DspFilters) -> Unit,
) {
    val effective = sampleRate?.let(filters::effectiveAt) ?: filters
    Text(stringResource(R.string.sound_filters_summary), style = MaterialTheme.typography.bodySmall)
    CutoffControl(
        stringResource(R.string.sound_filter_high_pass), filters.highPassHz, effective.highPassHz,
        DspFilters.HIGH_PASS_RANGE, 65f, sampleRate, enabled,
    ) { onChange(filters.copy(highPassHz = it)) }
    CutoffControl(
        stringResource(R.string.sound_filter_low_pass), filters.lowPassHz, effective.lowPassHz,
        DspFilters.LOW_PASS_RANGE, 12000f, sampleRate, enabled,
    ) { onChange(filters.copy(lowPassHz = it)) }
    SoundSlider(
        stringResource(R.string.sound_filter_low_shelf), filters.lowShelfDb, DspFilters.SHELF_RANGE,
        enabled && usableFrequency(DspFilters.LOW_SHELF_HZ, sampleRate), "%.1f dB".format(filters.lowShelfDb),
    ) { onChange(filters.copy(lowShelfDb = it)) }
    SoundSlider(
        stringResource(R.string.sound_filter_high_shelf), filters.highShelfDb, DspFilters.SHELF_RANGE,
        enabled && usableFrequency(DspFilters.HIGH_SHELF_HZ, sampleRate), "%.1f dB".format(filters.highShelfDb),
    ) { onChange(filters.copy(highShelfDb = it)) }
    if (!usableFrequency(DspFilters.HIGH_SHELF_HZ, sampleRate)) {
        Text(stringResource(R.string.sound_filter_rate_limit), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CutoffControl(
    label: String,
    cutoff: Float,
    effectiveCutoff: Float,
    range: ClosedFloatingPointRange<Float>,
    defaultHz: Float,
    sampleRate: Int?,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    val upper = min(range.endInclusive, sampleRate?.let { (it * 0.48 - 1).toFloat() } ?: range.endInclusive)
    val supported = upper > range.start
    SoundSwitch(label, cutoff > 0f, enabled && (supported || cutoff > 0f)) {
        onChange(if (it) defaultHz.coerceAtMost(upper) else 0f)
    }
    if (cutoff > 0f && supported) {
        SoundSlider(
            stringResource(R.string.sound_filter_cutoff, label), cutoff.coerceAtMost(upper),
            range.start..upper, enabled, "${cutoff.roundToInt()} Hz",
        ) { onChange(it.roundToInt().toFloat()) }
    }
    if (!supported || (cutoff > 0f && cutoff != effectiveCutoff)) {
        val message = if (effectiveCutoff > 0f) {
            stringResource(R.string.sound_filter_rate_adjusted, effectiveCutoff.roundToInt())
        } else stringResource(R.string.sound_filter_rate_limit)
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

private fun usableFrequency(frequency: Float, rate: Int?) = rate == null || frequency < rate * 0.48

@Composable
internal fun SoundResponseGraph(config: DspConfig, sampleRate: Int?, analysis: TrackAnalysis?) {
    val rate = sampleRate ?: 48000
    val maxFrequency = min(20000.0, rate * 0.48).coerceAtLeast(20.0)
    val response = remember(config, rate, analysis) {
        val engine = PcmDspEngine(rate, 1)
        engine.configure(config, analysis)
        FloatArray(160) { index ->
            val frequency = 20.0 * (maxFrequency / 20).pow(index / 159.0)
            engine.responseDb(frequency).coerceIn(-24.0, 24.0).toFloat()
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(R.string.sound_response_title)
    Text(description, style = MaterialTheme.typography.labelLarge)
    Text(
        stringResource(R.string.sound_response_scale, "%.2f".format(maxFrequency / 1000)),
        style = MaterialTheme.typography.bodySmall,
    )
    if (sampleRate == null) {
        Text(stringResource(R.string.sound_response_reference), style = MaterialTheme.typography.bodySmall)
    }
    Canvas(Modifier.fillMaxWidth().height(112.dp).semantics { contentDescription = description }) {
        for (position in 0..4) {
            val y = size.height * position / 4
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = if (position == 2) 2f else 1f)
        }
        val path = Path()
        response.forEachIndexed { index, value ->
            val x = size.width * index / (response.size - 1)
            val y = size.height * (0.5f - value / 48f)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, primary, style = Stroke(2.dp.toPx()))
    }
}
