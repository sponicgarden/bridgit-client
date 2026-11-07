package cc.bridgit.app.sound

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A loud sound's fingerprint, made on the phone (the sound twin of the camera's free change check): how its energy
 * spreads over [BANDS] frequency bands, averaged over the frames that aren't quiet, plus how long it lasted. Two
 * clips with a close fingerprint are "the same kind of sound", so the answer Clef gave for the first can be reused
 * for the second ([SoundRules.decide]). Only these few numbers are kept, never the audio. Pure.
 */
class SoundPrint(val bands: DoubleArray, val seconds: Double) {
    companion object {
        const val RATE = 16_000
        const val FRAME = 512
        const val BANDS = 12
        /** Cosine at or above this: the same kind of sound. Measured 4 Nov 2026: two halves of one bark 0.96, a quieter copy 1.00, bark vs chime 0.6, bark vs noise < 0. */
        const val SIMILAR = 0.85
        /** And their lengths at most this many times apart. */
        const val MAX_LENGTH_RATIO = 5.0
        private const val LO_HZ = 100.0
        private const val HI_HZ = 7_000.0
        private val EDGES = DoubleArray(BANDS + 1) { LO_HZ * (HI_HZ / LO_HZ).pow(it / BANDS.toDouble()) }
        private val WINDOW = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / (FRAME - 1)) }
        private val COS = Array(FRAME / 2) { k -> DoubleArray(FRAME) { n -> cos(2 * PI * k * n / FRAME) } }
        private val SIN = Array(FRAME / 2) { k -> DoubleArray(FRAME) { n -> sin(2 * PI * k * n / FRAME) } }

        /** From a 16 kHz mono 16-bit WAV (header skipped) or raw PCM; null when it is too short or all quiet. */
        fun of(wav: ByteArray): SoundPrint? {
            val start = if (wav.size > 44 && wav[0] == 'R'.code.toByte() && wav[1] == 'I'.code.toByte()) 44 else 0
            val n = (wav.size - start) / 2
            if (n < FRAME) return null
            val x = DoubleArray(n) { ((wav[start + 2 * it + 1].toInt() shl 8) or (wav[start + 2 * it].toInt() and 0xff)).toShort().toDouble() }
            val frames = n / FRAME
            val rms = DoubleArray(frames) { f -> var s = 0.0; for (i in 0 until FRAME) { val v = x[f * FRAME + i]; s += v * v }; sqrt(s / FRAME) }
            val floor = maxOf(200.0, (rms.maxOrNull() ?: 0.0) * 0.1)
            val sum = DoubleArray(BANDS)
            var used = 0
            val bandOf = IntArray(FRAME / 2) { k -> val hz = k * RATE.toDouble() / FRAME; EDGES.indexOfLast { it <= hz }.takeIf { hz < HI_HZ } ?: -1 }
            for (f in 0 until frames) {
                if (rms[f] < floor) continue
                val e = DoubleArray(BANDS)
                for (k in 1 until FRAME / 2) {
                    val b = bandOf[k]; if (b !in 0 until BANDS) continue
                    var re = 0.0; var im = 0.0
                    val c = COS[k]; val s = SIN[k]
                    for (i in 0 until FRAME) { val v = x[f * FRAME + i] * WINDOW[i]; re += v * c[i]; im -= v * s[i] }
                    e[b] += re * re + im * im
                }
                // Shape only: each frame's log band energies, minus their mean, so loudness doesn't matter.
                val logs = DoubleArray(BANDS) { ln(e[it] + 1.0) }
                val mean = logs.average()
                for (b in 0 until BANDS) sum[b] += logs[b] - mean
                used++
            }
            if (used == 0) return null
            val v = DoubleArray(BANDS) { sum[it] / used }
            val norm = sqrt(v.sumOf { it * it }).takeIf { it > 1e-9 } ?: return null
            return SoundPrint(DoubleArray(BANDS) { v[it] / norm }, n / RATE.toDouble())
        }
    }

    fun cosine(o: SoundPrint): Double = bands.indices.sumOf { bands[it] * o.bands[it] }

    fun similar(o: SoundPrint?): Boolean = o != null && cosine(o) >= SIMILAR &&
        maxOf(seconds, o.seconds) <= MAX_LENGTH_RATIO * minOf(seconds, o.seconds).coerceAtLeast(0.05)
}
