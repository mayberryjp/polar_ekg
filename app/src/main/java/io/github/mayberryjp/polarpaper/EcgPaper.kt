package io.github.mayberryjp.polarpaper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun EcgPaper(samples: List<EcgPoint>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text("25 mm/s  |  10 mm/mV  |  1 mV calibration  |  schematic grid")
        Canvas(Modifier.fillMaxWidth().height(240.dp).background(Color(0xFFFFF8F8))) {
            val mm = 4.dp.toPx()
            val minor = Color(0xFFF5DCDD)
            val major = Color(0xFFE9AFB4)
            var i = 0
            while (i * mm <= size.width) {
                drawLine(if (i % 5 == 0) major else minor, Offset(i * mm, 0f),
                    Offset(i * mm, size.height), if (i % 5 == 0) 1.2f else 0.6f)
                i++
            }
            i = 0
            while (i * mm <= size.height) {
                drawLine(if (i % 5 == 0) major else minor, Offset(0f, i * mm),
                    Offset(size.width, i * mm), if (i % 5 == 0) 1.2f else 0.6f)
                i++
            }
            val baseline = size.height / 2
            val calibration = Path().apply {
                moveTo(mm, baseline)
                lineTo(2 * mm, baseline)
                lineTo(2 * mm, baseline - 10 * mm)
                lineTo(7 * mm, baseline - 10 * mm)
                lineTo(7 * mm, baseline)
                lineTo(8 * mm, baseline)
            }
            drawPath(calibration, Color(0xFF006C67), style = Stroke(1.5.dp.toPx()))
            if (samples.isNotEmpty()) {
                val end = samples.last().sensorNs
                val xOrigin = 10 * mm
                val spanSeconds = (size.width - xOrigin) / (25 * mm)
                val path = Path()
                var previousNs: Long? = null
                samples.forEach { sample ->
                    val ageSeconds = (end - sample.sensorNs) / 1e9
                    if (ageSeconds <= spanSeconds && ageSeconds >= 0) {
                        val x = size.width - (ageSeconds * 25 * mm).toFloat()
                        val y = baseline - sample.microVolts / 1000f * 10 * mm
                        // Do not draw a line across missing packets.
                        if (previousNs == null || sample.sensorNs - previousNs!! > 30_000_000L) path.moveTo(x, y)
                        else path.lineTo(x, y)
                        previousNs = sample.sensorNs
                    }
                }
                drawPath(path, Color(0xFF173E3B), style = Stroke(1.4.dp.toPx()))
            }
        }
    }
}
