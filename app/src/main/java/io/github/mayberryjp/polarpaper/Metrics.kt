package io.github.mayberryjp.polarpaper

import kotlin.math.abs
import kotlin.math.sqrt

data class Metrics(
    val rrCount: Int = 0,
    val rejectedRr: Int = 0,
    val pairCount: Int = 0,
    val meanRrMs: Double? = null,
    val rmssdMs: Double? = null,
    val sdnnMs: Double? = null,
    val pnn50Percent: Double? = null,
    val meanHr: Double? = null,
    val minHr: Int? = null,
    val maxHr: Int? = null,
    val hrCount: Int = 0,
)

data class RrPoint(val elapsedMs: Long, val rrMs: Int, val contact: Boolean, val breakBefore: Boolean)
data class HrPoint(val elapsedMs: Long, val bpm: Int, val contact: Boolean)
data class EcgPoint(val sensorNs: Long, val microVolts: Int)

/** Bounds screening is not ectopic-beat detection; these are RR-derived HRV estimates. */
class MetricAccumulator {
    private var n = 0
    private var rejected = 0
    private var mean = 0.0
    private var m2 = 0.0
    private var previous: Int? = null
    private var pairs = 0
    private var squaredDifferences = 0.0
    private var nn50 = 0
    private var hrN = 0
    private var hrMean = 0.0
    private var low: Int? = null
    private var high: Int? = null

    fun addRr(rrMs: Int, contact: Boolean = true, breakBefore: Boolean = false) {
        if (breakBefore) previous = null
        if (!contact || rrMs !in 250..2000) {
            rejected++
            previous = null
            return
        }
        n++
        val delta = rrMs - mean
        mean += delta / n
        m2 += delta * (rrMs - mean)
        previous?.let {
            val difference = rrMs - it
            pairs++
            squaredDifferences += difference.toDouble() * difference
            if (abs(difference) > 50) nn50++
        }
        previous = rrMs
    }

    fun breakRrSequence() { previous = null }

    fun addHr(bpm: Int, contact: Boolean = true) {
        if (!contact || bpm !in 1..240) return
        hrN++
        hrMean += (bpm - hrMean) / hrN
        low = low?.coerceAtMost(bpm) ?: bpm
        high = high?.coerceAtLeast(bpm) ?: bpm
    }

    fun snapshot() = Metrics(
        rrCount = n, rejectedRr = rejected, pairCount = pairs,
        meanRrMs = mean.takeIf { n > 0 },
        rmssdMs = if (pairs > 0) sqrt(squaredDifferences / pairs) else null,
        sdnnMs = if (n > 1) sqrt(m2.coerceAtLeast(0.0) / (n - 1)) else null,
        pnn50Percent = if (pairs > 0) 100.0 * nn50 / pairs else null,
        meanHr = hrMean.takeIf { hrN > 0 }, minHr = low, maxHr = high, hrCount = hrN,
    )
}

fun windowMetrics(rr: Iterable<RrPoint>, hr: Iterable<HrPoint>, nowMs: Long, windowMs: Long = 60_000): Metrics {
    val accumulator = MetricAccumulator()
    rr.filter { it.elapsedMs in (nowMs - windowMs)..nowMs }.forEach {
        accumulator.addRr(it.rrMs, it.contact, it.breakBefore)
    }
    hr.filter { it.elapsedMs in (nowMs - windowMs)..nowMs }.forEach {
        accumulator.addHr(it.bpm, it.contact)
    }
    return accumulator.snapshot()
}
