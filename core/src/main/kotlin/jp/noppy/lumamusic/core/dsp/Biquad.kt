package jp.noppy.lumamusic.core.dsp

import kotlin.math.*

internal class ResponsePoint(frequency: Double, rate: Int) {
    private val omega = 2 * PI * frequency / rate
    val cos1 = cos(omega)
    val cos2 = cos(2 * omega)
    val sin1 = sin(omega)
    val sin2 = sin(2 * omega)
}

internal data class Coeff(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    fun responsePower(point: ResponsePoint): Double {
        val realNumerator = b0 + b1 * point.cos1 + b2 * point.cos2
        val imagNumerator = b1 * point.sin1 + b2 * point.sin2
        val realDenominator = 1 + a1 * point.cos1 + a2 * point.cos2
        val imagDenominator = a1 * point.sin1 + a2 * point.sin2
        return (realNumerator * realNumerator + imagNumerator * imagNumerator) /
            (realDenominator * realDenominator + imagDenominator * imagDenominator)
    }
}

// RBJ formulas (https://www.w3.org/TR/audio-eq-cookbook/), with the denominator normalized to a0 = 1.
private fun biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
    Coeff(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

internal fun peakCoeff(rate: Int, frequency: Double, gain: Double): Coeff {
    val amplitude = 10.0.pow(gain / 40)
    val omega = 2 * PI * frequency / rate
    val alpha = sin(omega) / (2 * 1.2)
    val a1 = -2 * cos(omega)
    return biquad(1 + alpha * amplitude, a1, 1 - alpha * amplitude, 1 + alpha / amplitude, a1, 1 - alpha / amplitude)
}

internal fun passCoeff(rate: Int, frequency: Float, high: Boolean): Coeff {
    val omega = 2 * PI * frequency / rate
    val cos = cos(omega)
    val alpha = sin(omega) / sqrt(2.0) // Butterworth Q = 1/sqrt(2).
    val b0 = if (high) (1 + cos) / 2 else (1 - cos) / 2
    return biquad(b0, if (high) -2 * b0 else 2 * b0, b0, 1 + alpha, -2 * cos, 1 - alpha)
}

internal fun shelfCoeff(rate: Int, frequency: Float, gain: Float, high: Boolean): Coeff {
    val amplitude = 10.0.pow(gain.toDouble() / 40)
    val omega = 2 * PI * frequency / rate
    val plus = amplitude + 1
    val minus = amplitude - 1
    val plusCos = plus * cos(omega)
    val minusCos = minus * cos(omega)
    val slope = sin(omega) * sqrt(2 * amplitude) // Shelf slope S = 1.
    return if (high) {
        biquad(
            amplitude * (plus + minusCos + slope),
            -2 * amplitude * (minus + plusCos),
            amplitude * (plus + minusCos - slope),
            plus - minusCos + slope,
            2 * (minus - plusCos),
            plus - minusCos - slope,
        )
    } else {
        biquad(
            amplitude * (plus - minusCos + slope),
            2 * amplitude * (minus - plusCos),
            amplitude * (plus - minusCos - slope),
            plus + minusCos + slope,
            -2 * (minus + plusCos),
            plus + minusCos - slope,
        )
    }
}

internal class Filter(private val c: Coeff) {
    private var z1 = 0.0
    private var z2 = 0.0

    // Transposed direct form II with independent state for each PCM channel.
    fun sample(x: Double): Double {
        val y = c.b0 * x + z1
        z1 = c.b1 * x - c.a1 * y + z2
        z2 = c.b2 * x - c.a2 * y
        if (!y.isFinite() || !z1.isFinite() || !z2.isFinite() ||
            (x == 0.0 && abs(z1) + abs(z2) < 1e-30)
        ) {
            z1 = 0.0
            z2 = 0.0
            return 0.0
        }
        return y
    }
}
