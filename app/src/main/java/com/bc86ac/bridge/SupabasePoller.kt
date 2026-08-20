package com.bc86ac.bridge

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls the print_jobs table in Supabase for pending jobs and prints them.
 *
 * This exists because a Vercel-hosted app has no way to reach a printer on
 * a private LAN directly -- so instead of the cloud pushing jobs in, the
 * tablet pulls them out via an ordinary outbound HTTPS request, same as any
 * app checking for updates. See print_jobs_schema.sql for the table this
 * expects, and actions.ts for the enqueue side.
 */
class SupabasePoller(private val context: Context) {

    companion object {
        private const val TAG = "SupabasePoller"
        private const val POLL_INTERVAL_MS = 2000L

        @Volatile var lastPollAt: Long = 0
        @Volatile var lastError: String? = null
        @Volatile var lastPrintedJobId: String? = null
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    fun start() {
        if (running.get()) return
        running.set(true)
        thread = Thread { loop() }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running.get()) {
            try {
                val (url, key) = readConfig()
                if (url != null && key != null) {
                    pollOnce(url, key)
                    lastError = null
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Poll failed"
                Log.w(TAG, "Poll error", e)
            }
            lastPollAt = System.currentTimeMillis()
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun readConfig(): Pair<String?, String?> {
        val prefs = context.getSharedPreferences(PrintBridgeService.PREFS_NAME, Context.MODE_PRIVATE)
        val url = prefs.getString("supabase_url", null)?.trimEnd('/')
        val key = prefs.getString("supabase_anon_key", null)
        return Pair(url?.takeIf { it.isNotBlank() }, key?.takeIf { it.isNotBlank() })
    }

    private fun pollOnce(baseUrl: String, apiKey: String) {
        val jobs = fetchPendingJobs(baseUrl, apiKey)
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            val id = job.getString("id")
            val payloadBase64 = job.getString("payload_base64")

            try {
                updateJobStatus(baseUrl, apiKey, id, "printing", null)
                val bytes = Base64.decode(payloadBase64, Base64.DEFAULT)
                PrinterBridge.printAuto(context, bytes)
                updateJobStatus(baseUrl, apiKey, id, "done", null)
                lastPrintedJobId = id
            } catch (e: Exception) {
                Log.w(TAG, "Print failed for job $id", e)
                try {
                    updateJobStatus(baseUrl, apiKey, id, "error", e.message ?: "Print failed")
                } catch (_: Exception) {
                    // if even the status update fails, next poll will retry naturally
                }
            }
        }
    }

    private fun fetchPendingJobs(baseUrl: String, apiKey: String): JSONArray {
        val url = URL(
            "$baseUrl/rest/v1/print_jobs" +
                "?select=id,payload_base64" +
                "&status=eq.pending" +
                "&order=created_at.asc" +
                "&limit=5"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("apikey", apiKey)
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Supabase GET returned $code")
            }
            val body = conn.inputStream.bufferedReader().readText()
            JSONArray(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun updateJobStatus(baseUrl: String, apiKey: String, jobId: String, status: String, error: String?) {
        val url = URL("$baseUrl/rest/v1/print_jobs?id=eq.$jobId")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "PATCH"
        conn.setRequestProperty("apikey", apiKey)
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Prefer", "return=minimal")
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = 8000

        val body = JSONObject().apply {
            put("status", status)
            if (error != null) put("error", error)
            if (status == "done") put("printed_at", isoNow())
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Supabase PATCH returned $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }
}
