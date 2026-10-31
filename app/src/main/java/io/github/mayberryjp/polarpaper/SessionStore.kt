package io.github.mayberryjp.polarpaper

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Locale

data class SessionSummary(
    val id: Long, val startedMs: Long, val durationMs: Long, val device: String,
    val sampleRate: Int, val status: String, val ecgSamples: Long, val metrics: Metrics,
)

class SessionStore(context: Context, databaseName: String = "sessions.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE sessions (
            id INTEGER PRIMARY KEY, started_ms INTEGER NOT NULL, duration_ms INTEGER NOT NULL DEFAULT 0,
            device TEXT NOT NULL, sample_rate INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'Recording')""")
        db.execSQL("""CREATE TABLE ecg (
            session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            sensor_ns INTEGER NOT NULL, microvolts INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE rr (
            session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            elapsed_ms INTEGER NOT NULL, rr_ms INTEGER NOT NULL, contact INTEGER NOT NULL, break_before INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE hr (
            session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
            elapsed_ms INTEGER NOT NULL, bpm INTEGER NOT NULL, contact INTEGER NOT NULL)""")
        listOf("ecg", "rr", "hr").forEach { db.execSQL("CREATE INDEX ${it}_session ON $it(session_id)") }
    }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("No migration from database $oldVersion to $newVersion")
    }

    suspend fun recover() = withContext(Dispatchers.IO) {
        writableDatabase.execSQL("UPDATE sessions SET status = 'Interrupted: app process stopped' WHERE status = 'Recording'")
    }

    suspend fun begin(device: String, sampleRate: Int): Long = withContext(Dispatchers.IO) {
        writableDatabase.insertOrThrow("sessions", null, ContentValues().apply {
            put("started_ms", System.currentTimeMillis())
            put("device", device)
            put("sample_rate", sampleRate)
        })
    }

    suspend fun saveEcg(id: Long, elapsed: Long, samples: List<EcgPoint>) = withContext(Dispatchers.IO) {
        transaction {
            compileStatement("INSERT INTO ecg VALUES (?, ?, ?)").use { statement ->
                samples.forEach {
                    statement.bindLong(1, id)
                    statement.bindLong(2, it.sensorNs)
                    statement.bindLong(3, it.microVolts.toLong())
                    statement.executeInsert()
                }
            }
            updateDuration(id, elapsed)
        }
    }

    suspend fun saveHr(id: Long, elapsed: Long, hr: List<HrPoint>, rr: List<RrPoint>) = withContext(Dispatchers.IO) {
        transaction {
            compileStatement("INSERT INTO hr VALUES (?, ?, ?, ?)").use { statement ->
                hr.forEach {
                    statement.bindLong(1, id)
                    statement.bindLong(2, it.elapsedMs)
                    statement.bindLong(3, it.bpm.toLong())
                    statement.bindLong(4, if (it.contact) 1 else 0)
                    statement.executeInsert()
                }
            }
            compileStatement("INSERT INTO rr VALUES (?, ?, ?, ?, ?)").use { statement ->
                rr.forEach {
                    statement.bindLong(1, id)
                    statement.bindLong(2, it.elapsedMs)
                    statement.bindLong(3, it.rrMs.toLong())
                    statement.bindLong(4, if (it.contact) 1 else 0)
                    statement.bindLong(5, if (it.breakBefore) 1 else 0)
                    statement.executeInsert()
                }
            }
            updateDuration(id, elapsed)
        }
    }

    suspend fun finish(id: Long, duration: Long, status: String) = withContext(Dispatchers.IO) {
        writableDatabase.update("sessions", ContentValues().apply {
            put("duration_ms", duration)
            put("status", status)
        }, "id = ?", arrayOf(id.toString()))
    }

    suspend fun listSessions(): List<SessionSummary> = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT * FROM sessions WHERE status != 'Recording' ORDER BY started_ms DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    add(SessionSummary(
                        id, cursor.getLong(1), cursor.getLong(2), cursor.getString(3),
                        cursor.getInt(4), cursor.getString(5), countEcg(id), metrics(id),
                    ))
                }
            }
        }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        check(writableDatabase.delete("sessions", "id = ? AND status != 'Recording'", arrayOf(id.toString())) == 1) {
            "Session could not be deleted"
        }
    }

    suspend fun export(summary: SessionSummary, directory: File): File = withContext(Dispatchers.IO) {
        check(directory.exists() || directory.mkdirs()) { "Unable to create report folder" }
        val file = File(directory, "polar-session-${summary.id}.zip")
        val temporary = File(directory, "polar-session-${summary.id}.tmp")
        try {
            java.util.zip.ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                fun entry(name: String, write: (java.io.Writer) -> Unit) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    val writer = zip.writer(Charsets.UTF_8)
                    write(writer)
                    writer.flush()
                    zip.closeEntry()
                }
                entry("report.txt") { it.write(reportText(summary)) }
                entry("metrics.json") { it.write(reportJson(summary)) }
                entry("ecg.csv") { writer ->
                    writer.write("sensor_timestamp_ns_since_2000,relative_seconds,microvolts\n")
                    readableDatabase.rawQuery("SELECT sensor_ns, microvolts FROM ecg WHERE session_id = ? ORDER BY rowid", arrayOf(summary.id.toString())).use { cursor ->
                        var first: Long? = null
                        while (cursor.moveToNext()) {
                            val ns = cursor.getLong(0)
                            if (first == null) first = ns
                            writer.write("$ns,${String.format(Locale.US, "%.9f", (ns - first) / 1e9)},${cursor.getInt(1)}\n")
                        }
                    }
                }
                entry("rr.csv") { writer ->
                    writer.write("elapsed_ms,rr_ms,contact,break_before,accepted\n")
                    readableDatabase.rawQuery("SELECT elapsed_ms,rr_ms,contact,break_before FROM rr WHERE session_id = ? ORDER BY rowid", arrayOf(summary.id.toString())).use { cursor ->
                        while (cursor.moveToNext()) {
                            val rr = cursor.getInt(1)
                            val contact = cursor.getInt(2)
                            writer.write("${cursor.getLong(0)},$rr,$contact,${cursor.getInt(3)},${if (contact == 1 && rr in 250..2000) 1 else 0}\n")
                        }
                    }
                }
                entry("hr.csv") { writer ->
                    writer.write("elapsed_ms,bpm,contact\n")
                    readableDatabase.rawQuery("SELECT elapsed_ms,bpm,contact FROM hr WHERE session_id = ? ORDER BY rowid", arrayOf(summary.id.toString())).use { cursor ->
                        while (cursor.moveToNext()) writer.write("${cursor.getLong(0)},${cursor.getInt(1)},${cursor.getInt(2)}\n")
                    }
                }
            }
            check(temporary.renameTo(file)) { "Unable to finalize report export" }
            file
        } finally {
            if (temporary.exists()) check(temporary.delete()) { "Unable to clean up incomplete export" }
        }
    }

    private fun countEcg(id: Long): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM ecg WHERE session_id = ?", arrayOf(id.toString())).use {
        it.moveToFirst()
        it.getLong(0)
    }

    private fun metrics(id: Long): Metrics {
        val accumulator = MetricAccumulator()
        readableDatabase.rawQuery("SELECT rr_ms,contact,break_before FROM rr WHERE session_id = ? ORDER BY rowid", arrayOf(id.toString())).use {
            while (it.moveToNext()) accumulator.addRr(it.getInt(0), it.getInt(1) == 1, it.getInt(2) == 1)
        }
        readableDatabase.rawQuery("SELECT bpm,contact FROM hr WHERE session_id = ? ORDER BY rowid", arrayOf(id.toString())).use {
            while (it.moveToNext()) accumulator.addHr(it.getInt(0), it.getInt(1) == 1)
        }
        return accumulator.snapshot()
    }

    private fun SQLiteDatabase.updateDuration(id: Long, elapsed: Long) {
        execSQL("UPDATE sessions SET duration_ms = MAX(duration_ms, ?) WHERE id = ?", arrayOf(elapsed, id))
    }

    private inline fun transaction(block: SQLiteDatabase.() -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.block()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

fun Double?.display(digits: Int = 1): String = this?.let { String.format(Locale.US, "%.${digits}f", it) } ?: "--"
fun durationText(ms: Long): String = String.format(Locale.US, "%02d:%02d:%02d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60)
fun reportText(s: SessionSummary): String = """
    POLAR PAPER - SESSION REPORT
    Started (UTC): ${Instant.ofEpochMilli(s.startedMs)}
    Duration: ${durationText(s.durationMs)}
    Device: ${s.device}
    Status: ${s.status}
    ECG: ${s.sampleRate} Hz, ${s.ecgSamples} saved samples

    Entire session (not the live 60-second window)
    Mean HR: ${s.metrics.meanHr.display()} bpm
    Min / max HR: ${s.metrics.minHr ?: "--"} / ${s.metrics.maxHr ?: "--"} bpm
    HRV RMSSD: ${s.metrics.rmssdMs.display()} ms
    SDNN (sample standard deviation): ${s.metrics.sdnnMs.display()} ms
    pNN50: ${s.metrics.pnn50Percent.display()} %
    Mean RR: ${s.metrics.meanRrMs.display()} ms
    Accepted / rejected RR: ${s.metrics.rrCount} / ${s.metrics.rejectedRr}
    Adjacent accepted RR pairs: ${s.metrics.pairCount}
    Valid HR readings: ${s.metrics.hrCount}

    Method: Polar H10 RR intervals in milliseconds. Accept 250-2000 ms with skin
    contact; HR accepts 1-240 bpm with contact. No pairs across rejected beats,
    missing RR, or notification gaps >5 s. RMSSD = sqrt(mean successive RR
    difference squared); SDNN uses n-1; pNN50 counts differences strictly >50 ms.
    No ectopic detection/correction: NN metrics are estimates from screened RR.
    RR elapsed times are reconstructed relative to notification arrival, not
    synchronized ECG R-peak timestamps. Mean HR is a mean of received readings,
    not time-weighted. "--" means insufficient data. Short sessions may be unstable;
    use at least 5 minutes at rest for comparable short-term HRV.

    Single-lead wellness/research capture, NOT a diagnostic ECG or medical advice.
    Exports contain sensitive health data. Share only with trusted recipients.
""".trimIndent()

fun reportJson(s: SessionSummary): String {
    val m = s.metrics
    return org.json.JSONObject().apply {
        put("schemaVersion", 1)
        put("sessionId", s.id)
        put("startedUtc", Instant.ofEpochMilli(s.startedMs).toString())
        put("durationMs", s.durationMs)
        put("device", s.device)
        put("status", s.status)
        put("sampleRateHz", s.sampleRate)
        put("ecgSampleCount", s.ecgSamples)
        put("scope", "entire_session")
        put("metrics", org.json.JSONObject().apply {
            put("rmssdMs", m.rmssdMs ?: org.json.JSONObject.NULL)
            put("sdnnMs", m.sdnnMs ?: org.json.JSONObject.NULL)
            put("pnn50Percent", m.pnn50Percent ?: org.json.JSONObject.NULL)
            put("meanRrMs", m.meanRrMs ?: org.json.JSONObject.NULL)
            put("meanHrBpm", m.meanHr ?: org.json.JSONObject.NULL)
            put("minHrBpm", m.minHr ?: org.json.JSONObject.NULL)
            put("maxHrBpm", m.maxHr ?: org.json.JSONObject.NULL)
            put("acceptedRrCount", m.rrCount)
            put("rejectedRrCount", m.rejectedRr)
            put("adjacentPairCount", m.pairCount)
            put("validHrCount", m.hrCount)
        })
        put("method", "Bounds-screened RR, not clinically validated NN; see report.txt")
        put("diagnostic", false)
    }.toString(2)
}
