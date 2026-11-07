package cc.bridgit.app.log

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import cc.bridgit.app.api.ErrorCatalog
import cc.bridgit.app.core.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Android-owned buffers are snapshots, not a lifetime archive. Never runs caller-supplied shell text. */
object DeviceDiagnostics {
    const val MAX_BYTES = 2 * 1024 * 1024
    val BUFFERS = setOf("main", "system", "crash", "events", "all")

    fun command(buffer: String, lines: Int): List<String> {
        if (buffer !in BUFFERS) throw ErrorCatalog.error("invalid_parameter", "buffer must be main, system, crash, events or all.")
        if (lines !in 1..10_000) throw ErrorCatalog.error("invalid_parameter", "lines must be 1-10000.")
        return listOf("logcat", "-d", "-v", "threadtime", "-b", buffer, "-t", lines.toString())
    }

    /** Known credential formats are removed; arbitrary Android/vendor log content may still be sensitive. */
    fun scrub(s: String): String = Keys.scrub(s)
        .replace(Regex("(?i)(Bearer\\s+)[^\\s\"']+"), "$1[redacted]")
        .replace(Regex("(?i)((?:password|passwd|token|secret|api_key)\\s*[=:]\\s*)[^\\s,;\"']+"), "$1[redacted]")

    suspend fun appLog(buffer: String, lines: Int): JSONObject = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(command(buffer, lines)).redirectErrorStream(true).start()
        val bytes = java.io.ByteArrayOutputStream()
        var overflow = false
        val reader = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        synchronized(bytes) {
                            val room = MAX_BYTES - bytes.size()
                            if (n > room) overflow = true
                            if (room > 0) bytes.write(buf, 0, minOf(n, room))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val done = process.waitFor(10, TimeUnit.SECONDS)
            if (!done) process.destroyForcibly()
            reader.join(1000)
            val output = synchronized(bytes) { scrub(bytes.toString("UTF-8")) }
            JSONObject().put("scope", "app_uid").put("buffer", buffer).put("output", output)
                .put("exit_code", if (done) process.exitValue() else JSONObject.NULL)
                .put("truncated", overflow).put("timed_out", !done)
                .put("retention", "Android rotating buffers; app UID only. Use /v1/adb/logcat for system logs.")
        } finally { process.destroy(); runCatching { process.inputStream.close() } }
    }

    fun exits(ctx: Context): List<android.app.ApplicationExitInfo> =
        if (Build.VERSION.SDK_INT < 30) emptyList() else
            ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(ctx.packageName, 0, 0)

    fun exitJson(e: android.app.ApplicationExitInfo): JSONObject = JSONObject()
        .put("id", "${e.timestamp}:${e.pid}:${e.reason}").put("timestamp_ms", e.timestamp)
        .put("process", scrub(e.processName)).put("pid", e.pid).put("reason", e.reason).put("status", e.status)
        .put("description", e.description?.let(::scrub) ?: JSONObject.NULL)
        .put("importance", e.importance).put("pss_kib", e.pss).put("rss_kib", e.rss)

    /** Preserve OS exit summaries in Bridgit's persistent diagnostic history before Android ages them out. */
    fun archiveExits(ctx: Context, log: LogStore) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = ctx.getSharedPreferences("diagnostic_exits", Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen", emptySet())!!.toSet()
        val available = exits(ctx)
        val ids = mutableSetOf<String>()
        available.sortedBy { it.timestamp }.forEach { e ->
            val json = exitJson(e)
            val id = json.getString("id"); ids += id
            if (id !in seen) log.append(LogEntry(ts = e.timestamp, channel = "diagnostic", kind = "crash",
                level = if (e.reason in setOf(4, 5, 6)) "error" else "info",
                message = "Android process exit: ${e.reason} (status ${e.status})", data = json))
        }
        log.flush()
        prefs.edit().putStringSet("seen", ids).commit()
    }

    fun exitPage(ctx: Context, before: Long?, limit: Int, trace: Boolean): JSONObject {
        if (Build.VERSION.SDK_INT < 30) throw ErrorCatalog.error("not_supported", "Android exit history needs Android 11 or later.")
        val matches = exits(ctx).filter { before == null || it.timestamp < before }.sortedByDescending { it.timestamp }
        // Keep identical timestamps together, so a timestamp cursor never skips a tied record.
        val cutoff = matches.getOrNull(limit - 1)?.timestamp
        val page = if (cutoff == null) matches else matches.takeWhile { it.timestamp >= cutoff }
        var remaining = MAX_BYTES
        val entries = page.map { e -> exitJson(e).apply {
            if (trace && remaining <= 0) put("trace_omitted", "page_byte_budget")
            if (trace && remaining > 0) {
                // Native crash traces are protobuf; return those as base64, ANR traces as scrubbed UTF-8.
                val traceResult = runCatching { e.traceInputStream?.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                    while (out.size() <= remaining) {
                        val n = input.read(buf, 0, minOf(buf.size, remaining + 1 - out.size()))
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }; out.toByteArray()
                } }
                val raw = traceResult.getOrNull()
                traceResult.exceptionOrNull()?.let { put("trace_error", it.javaClass.simpleName) }
                put("trace_available", raw != null)
                if (raw != null) {
                    val bounded = raw.copyOf(minOf(raw.size, remaining))
                    put("trace_truncated", raw.size > remaining)
                    remaining -= bounded.size
                    if (e.reason == 5) {
                        put("trace_encoding", "base64-protobuf")
                        put("trace", android.util.Base64.encodeToString(bounded, android.util.Base64.NO_WRAP))
                    } else { put("trace_encoding", "utf-8"); put("trace", scrub(bounded.toString(Charsets.UTF_8))) }
                }
            }
        } }
        return JSONObject().put("entries", JSONArray(entries))
            .put("next_before", if (page.size < matches.size) page.last().timestamp else JSONObject.NULL)
            .put("retention", "Android-managed recent exits; persistent summaries: /v1/log?channel=diagnostic&kind=crash")
    }
}
