package io.github.mayberryjp.polarpaper

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.zip.ZipFile
import org.json.JSONObject

class AppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeScreenStartsWithoutBluetoothPermissions() {
        compose.onNodeWithText("POLAR PAPER").assertIsDisplayed()
        compose.onNodeWithText("Find H10").assertIsDisplayed()
    }

    @Test fun sessionRoundTripExportRecoveryAndDelete() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "test-session-${System.nanoTime()}.db"
        val store = SessionStore(context, database)
        var exported: java.io.File? = null
        try {
            store.recover()
            val id = store.begin("TEST-H10", 130)
            store.saveEcg(id, 2000, listOf(EcgPoint(1_000_000_000, 1000), EcgPoint(1_007_692_307, -500)))
            store.saveHr(id, 2000, listOf(HrPoint(1000, 60, true), HrPoint(2000, 80, true)),
                listOf(RrPoint(1000, 1000, true, true), RrPoint(2000, 1050, true, false)))
            store.finish(id, 2500, "Completed")
            val summary = store.listSessions().single()
            assertEquals(2L, summary.ecgSamples)
            assertEquals(60, summary.metrics.minHr)
            assertEquals(80, summary.metrics.maxHr)
            assertEquals(50.0, summary.metrics.rmssdMs!!, 1e-9)
            assertEquals(0.0, summary.metrics.pnn50Percent!!, 1e-9)
            exported = store.export(summary, java.io.File(context.cacheDir, "test-reports"))
            ZipFile(exported).use { zip ->
                assertEquals(setOf("report.txt", "metrics.json", "ecg.csv", "rr.csv", "hr.csv"),
                    zip.entries().asSequence().map { it.name }.toSet())
                val json = JSONObject(zip.getInputStream(zip.getEntry("metrics.json")).bufferedReader().readText())
                assertEquals("entire_session", json.getString("scope"))
                assertEquals(2, json.getJSONObject("metrics").getInt("acceptedRrCount"))
                val ecg = zip.getInputStream(zip.getEntry("ecg.csv")).bufferedReader().readLines()
                assertEquals("1000000000,0.000000000,1000", ecg[1])
                assertEquals(3, ecg.size)
            }
            val interrupted = store.begin("TEST-H10", 130)
            store.saveEcg(interrupted, 1500, listOf(EcgPoint(3_000_000_000, 10)))
            store.recover()
            assertTrue(store.listSessions().first { it.id == interrupted }.status.startsWith("Interrupted"))
            store.delete(id)
            assertEquals(1, store.listSessions().size)
        } finally {
            exported?.delete()
            store.close()
            context.deleteDatabase(database)
        }
    }
}
