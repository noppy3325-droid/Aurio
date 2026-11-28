package jp.noppy.lumamusic.ui

private const val NanosPerRevolution = 1_800_000_000.0  // 33 1/3 rpm

internal fun advanceVinylRotation(angle: Float, elapsedNanos: Long): Float =
    ((angle + 360.0 * elapsedNanos / NanosPerRevolution) % 360.0).toFloat()

internal data class VinylRecord<T>(val mediaId: String?, val content: T)

internal data class VinylExchange<T>(
    val current: VinylRecord<T>,
    val outgoing: VinylRecord<T>? = null,
) {
    fun show(next: VinylRecord<T>, animate: Boolean): VinylExchange<T> {
        if (!animate || next.mediaId == null || current.mediaId == null) {
            return if (next == current && outgoing == null) this else VinylExchange(next)
        }
        if (next == current) return this
        // An in-flight exchange keeps its departure and admits only the latest arrival.
        val departure = if (next.mediaId == current.mediaId) outgoing else outgoing ?: current
        return VinylExchange(next, departure)
    }

    fun finish(): VinylExchange<T> = VinylExchange(current)
}
