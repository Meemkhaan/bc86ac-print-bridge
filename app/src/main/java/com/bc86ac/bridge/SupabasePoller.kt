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
        @Volatile var lastJobsFound: Int = 0
        @Volatile var lastPollDetail: String? = null
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
                } else {
                    lastPollDetail = "No config set"
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Poll failed"
                lastPollDetail = "Exception: ${e.message}"
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
        lastJobsFound = jobs.length()
        if (jobs.length() == 0) {
            lastPollDetail = "GET ok, 0 pending jobs"
            return
        }

        val details = mutableListOf<String>()
        for (i in 0 until jobs.length()) {
            val job = jobs.getJSONObject(i)
            val id = job.getString("id")
            val payloadBase64 = job.getString("payload_base64")

            try {
                patchJobStatus(baseUrl, apiKey, id, "printing", null)
                val bytes = Base64.decode(payloadBase64, Base64.DEFAULT)
                PrinterBridge.printAuto(context, bytes)
                patchJobStatus(baseUrl, apiKey, id, "done", null)
                lastPrintedJobId = id
                details.add("$id → done")
            } catch (e: Exception) {
                Log.w(TAG, "Job $id failed: ${e.message}", e)
                lastError = "Job $id: ${e.message}"
                details.add("$id → error: ${e.message}")
                try {
                    patchJobStatus(baseUrl, apiKey, id, "error", e.message ?: "Print failed")
                } catch (e2: Exception) {
                    Log.w(TAG, "Could not record error for $id: ${e2.message}")
                    details.add("$id → error-recording also failed: ${e2.message}")
                    lastError = "Job $id: ${e.message}; status update also failed: ${e2.message}"
                }
            }
        }
        lastPollDetail = details.joinToString("; ")
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
            val body = conn.inputStream.bufferedReader().readText()
            Log.d(TAG, "GET $url → $code, body length=${body.length}")
            if (code !in 200..299) {
                throw IllegalStateException("Supabase GET returned $code: $body")
            }
            JSONArray(body)
        } catch (e: Exception) {
            Log.e(TAG, "GET failed: ${e.message}", e)
            lastError = "GET failed: ${e.message}"
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun patchJobStatus(baseUrl: String, apiKey: String, jobId: String, status: String, error: String?) {
        val body = JSONObject().apply {
            put("status", status)
            if (error != null) put("error", error)
            if (status == "done") put("printed_at", isoNow())
        }.toString()

        // Android's HttpURLConnection refuses the PATCH method outright
        // (throws ProtocolException immediately, before any request is
        // sent) -- a real, long-standing platform restriction, not
        // something fixable by config. So we build the PATCH request by
        // hand over a raw TLS socket instead, the same low-level approach
        // already used for the printer connection, just over HTTPS here.
        val url = URL("$baseUrl/rest/v1/print_jobs?id=eq.$jobId")
        val host = url.host
        val path = url.file
        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        val socketFactory = javax.net.ssl.SSLSocketFactory.getDefault()
        val socket = socketFactory.createSocket(host, 443) as javax.net.ssl.SSLSocket
        socket.soTimeout = 8000

        try {
            val request = buildString {
                append("PATCH $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("apikey: $apiKey\r\n")
                append("Authorization: Bearer $apiKey\r\n")
                append("Content-Type: application/json\r\n")
                append("Prefer: return=minimal\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            val out = socket.outputStream
            out.write(request.toByteArray(Charsets.UTF_8))
            out.write(bodyBytes)
            out.flush()

            val statusLine = readLineFromSocket(socket.inputStream)
                ?: throw IllegalStateException("No response from Supabase")
            // e.g. "HTTP/1.1 204 No Content" -- pull out the status code
            val parts = statusLine.split(" ")
            val code = parts.getOrNull(1)?.toIntOrNull()
                ?: throw IllegalStateException("Malformed response: $statusLine")

            if (code !in 200..299) {
                throw IllegalStateException("Supabase PATCH returned $code")
            }
            Log.d(TAG, "PATCH print_jobs?id=eq.$jobId → $code ($status)")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun testConnection(baseUrl: String, apiKey: String): String {
        return try {
            val jobs = fetchPendingJobs(baseUrl, apiKey)
            val count = jobs.length()
            val ids = mutableListOf<String>()
            for (i in 0 until count) {
                ids.add(jobs.getJSONObject(i).getString("id"))
            }
            "GET ok — $count pending job(s)" + if (ids.isNotEmpty()) ": ${ids.joinToString()}" else ""
        } catch (e: Exception) {
            "GET failed: ${e.message}"
        }
    }

    private fun readLineFromSocket(input: java.io.InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) buf.write(b)
            b = input.read()
        }
        return buf.toString("UTF-8")
    }

    private fun isoNow(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date())
    }
}
