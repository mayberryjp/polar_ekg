package io.github.mayberryjp.polarpaper

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class MetricsTest {
    @Test fun knownSequenceUsesSampleSdAndStrict50Threshold() {
        val a = MetricAccumulator()
        listOf(1000, 1050, 950, 1100).forEach { a.addRr(it) }
        val m = a.snapshot()
        assertEquals(1025.0, m.meanRrMs!!, 1e-9)
        assertEquals(sqrt(12500.0 / 3), m.sdnnMs!!, 1e-9)
        assertEquals(sqrt(35000.0 / 3), m.rmssdMs!!, 1e-9)
        assertEquals(200.0 / 3, m.pnn50Percent!!, 1e-9)
        assertEquals(3, m.pairCount)
    }

    @Test fun insufficientDataIsUnavailableNotZero() {
        val a = MetricAccumulator()
        assertNull(a.snapshot().sdnnMs)
        assertNull(a.snapshot().meanHr)
        a.addRr(800)
        assertNull(a.snapshot().rmssdMs)
        assertNull(a.snapshot().pnn50Percent)
        assertNull(a.snapshot().sdnnMs)
    }

    @Test fun rejectedAndDisconnectedBeatsNeverBridgePairs() {
        val a = MetricAccumulator()
        a.addRr(800)
        a.addRr(10)
        a.addRr(1200)
        a.addRr(1000, contact = false)
        a.addRr(700)
        a.addRr(1300, breakBefore = true)
        assertEquals(4, a.snapshot().rrCount)
        assertEquals(2, a.snapshot().rejectedRr)
        assertEquals(0, a.snapshot().pairCount)
        assertNull(a.snapshot().rmssdMs)
    }

    @Test fun boundaryIntervalsAndConstantSignal() {
        val a = MetricAccumulator()
        repeat(5) { a.addRr(1000) }
        assertEquals(0.0, a.snapshot().sdnnMs!!, 0.0)
        assertEquals(0.0, a.snapshot().rmssdMs!!, 0.0)
        val b = MetricAccumulator()
        listOf(249, 250, 2000, 2001).forEach { b.addRr(it) }
        assertEquals(2, b.snapshot().rrCount)
        assertEquals(2, b.snapshot().rejectedRr)
    }

    @Test fun heartRateUsesOnlyContactValidReadings() {
        val a = MetricAccumulator()
        listOf(60, 90, 75, 0, 255).forEach { a.addHr(it) }
        a.addHr(30, false)
        assertEquals(60, a.snapshot().minHr)
        assertEquals(90, a.snapshot().maxHr)
        assertEquals(75.0, a.snapshot().meanHr!!, 0.0)
    }

    @Test fun rollingWindowDoesNotUseExpiredBeatAsPrevious() {
        val rr = listOf(RrPoint(0, 800, true, false), RrPoint(61_000, 1000, true, true))
        val m = windowMetrics(rr, listOf(HrPoint(0, 75, true)), 61_000)
        assertEquals(1, m.rrCount)
        assertNull(m.rmssdMs)
        assertNull(m.minHr)
        assertEquals(0, windowMetrics(rr, emptyList(), 122_000).rrCount)
    }

    @Test fun longSequenceRemainsNumericallyStable() {
        val a = MetricAccumulator()
        repeat(100_000) { a.addRr(if (it % 2 == 0) 999 else 1001) }
        assertEquals(1000.0, a.snapshot().meanRrMs!!, 1e-6)
        assertEquals(2.0, a.snapshot().rmssdMs!!, 1e-9)
        assertEquals(sqrt(100_000.0 / 99_999), a.snapshot().sdnnMs!!, 1e-6)
    }
}
