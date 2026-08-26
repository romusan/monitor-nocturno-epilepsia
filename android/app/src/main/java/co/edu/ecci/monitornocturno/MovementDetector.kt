package co.edu.ecci.monitornocturno

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class DetectionState(val ratio: Double, val rhythmicity: Double, val candidate: Boolean)

class MovementDetector(
    private val sampleRate: Int = 50,
    private val persistenceSeconds: Int = 10,
    private val ratioThreshold: Double = 3.0
) {
    private val short = ArrayDeque<Double>()
    private val long = ArrayDeque<Double>()
    private val signal = ArrayDeque<Double>()
    private var consecutive = 0

    fun add(value: Double): DetectionState {
        val energy = value * value
        short.addLast(energy); long.addLast(energy); signal.addLast(value)
        trim(short, sampleRate); trim(long, sampleRate * 20); trim(signal, sampleRate * 4)
        val sta = short.averageOrZero()
        val lta = max(long.averageOrZero(), 1e-8)
        val ratio = sta / lta
        val rhythmicity = periodicity2To5Hz()
        val active = long.size >= sampleRate * 10 && ratio >= ratioThreshold && rhythmicity >= 0.35
        consecutive = if (active) consecutive + 1 else 0
        return DetectionState(ratio, rhythmicity, consecutive >= persistenceSeconds * sampleRate)
    }

    private fun periodicity2To5Hz(): Double {
        if (signal.size < sampleRate * 2) return 0.0
        val values = signal.toDoubleArray()
        val mean = values.average()
        var variance = 0.0
        for (v in values) variance += (v - mean) * (v - mean)
        if (variance < 1e-9) return 0.0
        var best = 0.0
        val minLag = sampleRate / 5
        val maxLag = sampleRate / 2
        for (lag in minLag..maxLag) {
            var covariance = 0.0
            for (i in lag until values.size) covariance += (values[i] - mean) * (values[i - lag] - mean)
            best = max(best, abs(covariance / variance))
        }
        return best.coerceIn(0.0, 1.0)
    }

    private fun trim(queue: ArrayDeque<Double>, limit: Int) { while (queue.size > limit) queue.removeFirst() }
    private fun ArrayDeque<Double>.averageOrZero() = if (isEmpty()) 0.0 else sum() / size
}

