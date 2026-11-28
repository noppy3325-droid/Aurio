package jp.noppy.lumamusic.core.dsp

enum class SoundMode { DIRECT, ENHANCE }
enum class NormalizeMode { TRACK, ALBUM }

data class DspFilters(
    val highPassHz: Float = 0f,
    val lowPassHz: Float = 0f,
    val lowShelfDb: Float = 0f,
    val highShelfDb: Float = 0f,
) {
    internal fun safe() = copy(
        highPassHz = if (highPassHz.isFinite() && highPassHz > 0f) highPassHz.coerceIn(HIGH_PASS_RANGE) else 0f,
        lowPassHz = if (lowPassHz.isFinite() && lowPassHz > 0f) lowPassHz.coerceIn(LOW_PASS_RANGE) else 0f,
        lowShelfDb = lowShelfDb.safeIn(SHELF_RANGE, 0f),
        highShelfDb = highShelfDb.safeIn(SHELF_RANGE, 0f),
    )

    /** The low-pass cutoff follows the available bandwidth. Fixed centers can only be bypassed. */
    fun effectiveAt(sampleRate: Int): DspFilters {
        val safe = safe()
        val limit = sampleRate * BIQUAD_RATE_LIMIT
        return safe.copy(
            highPassHz = safe.highPassHz.takeIf { it < limit } ?: 0f,
            lowPassHz = if (limit >= LOW_PASS_RANGE.start) minOf(safe.lowPassHz, limit.toFloat()) else 0f,
            lowShelfDb = safe.lowShelfDb.takeIf { LOW_SHELF_HZ < limit } ?: 0f,
            highShelfDb = safe.highShelfDb.takeIf { HIGH_SHELF_HZ < limit } ?: 0f,
        )
    }

    companion object {
        val HIGH_PASS_RANGE = 20f..180f
        val LOW_PASS_RANGE = 3000f..20000f
        val SHELF_RANGE = -9f..9f
        const val LOW_SHELF_HZ = 120f
        const val HIGH_SHELF_HZ = 6000f
    }
}

internal const val BIQUAD_RATE_LIMIT = .48

private fun Float.safeIn(range: ClosedFloatingPointRange<Float>, fallback: Float): Float =
    if (isFinite()) coerceIn(range) else fallback

data class DspConfig(
    val mode: SoundMode = SoundMode.DIRECT,
    val eqEnabled: Boolean = true,
    val bands: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,
    val autoEnabled: Boolean = false,
    val normalizeEnabled: Boolean = false,
    val normalizeMode: NormalizeMode = NormalizeMode.TRACK,
    val targetLufs: Float = -16f,
    val widthEnabled: Boolean = false,
    val width: Float = 1f,
    val preset: String = "フラット",
    val filters: DspFilters = DspFilters(),
    val comparisonTrimDb: Float = 0f,
) {
    fun safe() = copy(
        bands = List(10) { bands.getOrElse(it) { 0f }.safeIn(-12f..12f, 0f) },
        preampDb = preampDb.safeIn(-12f..6f, 0f),
        targetLufs = targetLufs.safeIn(-23f..-12f, -16f),
        width = width.safeIn(0f..1.2f, 1f),
        filters = filters.safe(),
        comparisonTrimDb = comparisonTrimDb.safeIn(COMPARISON_TRIM_RANGE, 0f),
    )

    companion object {
        val COMPARISON_TRIM_RANGE = -24f..0f
    }
}
val EQ_FREQUENCIES = doubleArrayOf(31.0,62.0,125.0,250.0,500.0,1000.0,2000.0,4000.0,8000.0,16000.0)
val FACTORY_PRESETS: Map<String,List<Float>> = linkedMapOf(
    "フラット" to List(10){0f}, "バランス" to listOf(1f,1f,0f,-.5f,0f,0f,.5f,1f,.5f,0f),
    "低音強調" to listOf(3f,3f,2f,1f,0f,0f,0f,0f,0f,0f),
    "ボーカル" to listOf(-1f,-1f,-.5f,0f,1f,2f,2f,1f,0f,-.5f),
    "明瞭感" to listOf(0f,0f,-.5f,-1f,0f,1f,1.5f,2f,1f,.5f),
    "ソフト" to listOf(0f,.5f,.5f,0f,0f,0f,-.5f,-1f,-2f,-2f),
)
data class TrackAnalysis(val estimatedLufs: Double?, val peak: Double, val toneGains: List<Float>, val frames: Long, val sampleRate: Int, val complete: Boolean, val tagGainDb: Double? = null, val tagKind: String? = null)
