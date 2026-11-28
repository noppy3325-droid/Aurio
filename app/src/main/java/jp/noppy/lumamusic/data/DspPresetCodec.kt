package jp.noppy.lumamusic.data

import jp.noppy.lumamusic.core.dsp.DspConfig
import jp.noppy.lumamusic.core.dsp.DspFilters
import jp.noppy.lumamusic.core.dsp.EQ_FREQUENCIES

// Keep the first ten fields in the original EQ-only format and append optional filter settings.
fun DspConfig.toDspPreset(name: String): DspPreset {
    val config = safe()
    val filters = config.filters
    val values = config.bands + listOf(filters.highPassHz, filters.lowPassHz, filters.lowShelfDb, filters.highShelfDb)
    return DspPreset(name, values.joinToString(","), config.preampDb)
}

fun DspPreset.applyTo(config: DspConfig): DspConfig {
    val values = bands.split(',').map { it.toFloatOrNull() ?: 0f }
    val bandCount = EQ_FREQUENCIES.size
    return config.copy(
        bands = values.take(bandCount),
        preampDb = preamp,
        preset = name,
        filters = DspFilters(
            highPassHz = values.getOrElse(bandCount) { 0f },
            lowPassHz = values.getOrElse(bandCount + 1) { 0f },
            lowShelfDb = values.getOrElse(bandCount + 2) { 0f },
            highShelfDb = values.getOrElse(bandCount + 3) { 0f },
        ),
    ).safe()
}
