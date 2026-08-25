package com.bc86ac.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Always-on foreground service that is the whole point of this app: it
 * replaces having to manually keep a Termux session open. It does two
 * things at once:
 *
 *  1. Runs a tiny embedded HTTP server on port 9876 (same API contract as
 *     the earlier Node.js print-bridge.js) so the Chrome extension and any
 *     web app (e.g. a Vercel-hosted ticket app) can POST raw ESC/POS bytes
 *     to it -- either to relay onward to a network printer, or to print
 *     directly over USB if this tablet is the one physically holding the
 *     printer's USB cable.
 *
 *  2. Talks to the BC-86AC directly over Android's USB Host API when a
 *     print job is meant for USB, so no separate WebUSB pairing step in
 *     the browser is needed for this path.
 *
 * Runs with START_STICKY and RECEIVE_BOOT_COMPLETED so it comes back after
 * the OS kills it under memory pressure or after a reboot -- see
 * BootReceiver.kt.
 */
class PrintBridgeService : Service() {

    companion object {
        private const val TAG = "PrintBridgeService"
        const val PORT = 9876
        const val COMPAT_PORT = 8765
        const val CHANNEL_ID = "bc86ac_bridge_channel"
        const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "bc86ac_bridge_prefs"
        const val PREF_USB_VENDOR_ID = "usb_vendor_id"
        const val PREF_USB_PRODUCT_ID = "usb_product_id"
        const val PREF_BRIDGE_SECRET = "bridge_secret"
        private const val ACTION_USB_PERMISSION = "com.bc86ac.bridge.USB_PERMISSION"

        // HMAC replay protection
        private const val HMAC_MAX_AGE_MS = 30_000L // 30 seconds
        private const val NONCE_TTL_MS = 60_000L // 60 seconds
        private const val HMAC_ALGO = "HmacSHA256"

        @Volatile var isRunning = false
            private set

        @Volatile var lastError: String? = null
            private set

        @Volatile var lastPrintAt: Long = 0
            private set
    }

    private lateinit var serverSocket: ServerSocket
    private lateinit var compatServerSocket: ServerSocket
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private lateinit var usbManager: UsbManager
    private lateinit var prefs: SharedPreferences
    private lateinit var poller: SupabasePoller
    private var usbPermissionReceiver: android.content.BroadcastReceiver? = null

    // HMAC replay protection: store used nonces with timestamps
    private val usedNonces = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        poller = SupabasePoller(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        if (!running.get()) {
            running.set(true)
            pool.execute { runServer() }
            pool.execute { runCompatServer() }
        }
        poller.start()
        autoRequestUsbPermission()
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        poller.stop()
        try { if (::serverSocket.isInitialized) serverSocket.close() } catch (_: Exception) {}
        try { if (::compatServerSocket.isInitialized) compatServerSocket.close() } catch (_: Exception) {}
        pool.shutdownNow()
        try {
            usbPermissionReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- HTTP server ----

    private fun runServer() {
        try {
            serverSocket = ServerSocket(PORT)
            isRunning = true
            lastError = null
            updateNotification("Listening on port $PORT")
            while (running.get()) {
                val client = try { serverSocket.accept() } catch (e: Exception) {
                    if (running.get()) { lastError = e.message }
                    break
                }
                pool.execute { handleClient(client) }
            }
        } catch (e: Exception) {
            isRunning = false
            lastError = e.message ?: "Failed to start server"
            updateNotification("Error: ${e.message}")
        }
    }

    private fun runCompatServer() {
        try {
            compatServerSocket = ServerSocket(COMPAT_PORT)
            while (running.get()) {
                val client = try { compatServerSocket.accept() } catch (e: Exception) {
                    if (running.get()) { lastError = e.message }
                    break
                }
                pool.execute { handleClient(client, true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Compat server error: ${e.message}")
        }
    }

    // ---- HMAC Replay Protection ----

    private fun getBridgeSecret(): String {
        return prefs.getString(PREF_BRIDGE_SECRET, "") ?: ""
    }

    private fun validateHmac(headers: Map<String, String>, body: ByteArray): Boolean {
        val secret = getBridgeSecret()
        if (secret.isBlank()) {
            Log.w(TAG, "Bridge secret not configured — rejecting request")
            return false
        }

        val tsHeader = headers["x-print-ts"] ?: headers["x-bridge-ts"]
        val sigHeader = headers["x-print-sig"] ?: headers["x-bridge-sig"]
        val nonceHeader = headers["x-print-nonce"] ?: headers["x-bridge-nonce"]

        if (tsHeader == null || sigHeader == null || nonceHeader == null) {
            Log.w(TAG, "Missing HMAC headers (ts/sig/nonce)")
            return false
        }

        val ts = tsHeader.toLongOrNull() ?: return false
        val now = System.currentTimeMillis()
        if (Math.abs(now - ts) > HMAC_MAX_AGE_MS) {
            Log.w(TAG, "Request timestamp too old/future: diff=${now - ts}ms")
            return false
        }

        // Check nonce replay
        val existingTs = usedNonces[nonceHeader]
        if (existingTs != null) {
            Log.w(TAG, "Replay detected: nonce=$nonceHeader")
            return false
        }
        usedNonces[nonceHeader] = now

        // Clean old nonces periodically
        if (usedNonces.size > 10000) {
            val cutoff = now - NONCE_TTL_MS
            usedNonces.entries.removeIf { it.value < cutoff }
        }

        // Compute expected signature: HMAC(secret, ts + ":" + nonce + ":" + bodyBase64)
        val bodyB64 = Base64.encodeToString(body, Base64.NO_WRAP)
        val signingInput = "$ts:$nonceHeader:$bodyB64"
        val expectedSig = computeHmac(secret, signingInput)

        if (!hmacCompare(sigHeader, expectedSig)) {
            Log.w(TAG, "HMAC signature mismatch")
            return false
        }

        return true
    }

    private fun computeHmac(secret: String, data: String): String {
        try {
            val keySpec = SecretKeySpec(secret.toByteArray(), HMAC_ALGO)
            val mac = Mac.getInstance(HMAC_ALGO)
            mac.init(keySpec)
            val result = mac.doFinal(data.toByteArray())
            return Base64.encodeToString(result, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "HMAC compute failed", e)
            return ""
        }
    }

    private fun hmacCompare(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in 0 until a.length) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    private fun handleClient(socket: Socket, isCompat: Boolean = false) {
        try {
            socket.soTimeout = 8000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readLineFromStream(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { writeResponse(output, 400, "text/plain", "Bad request".toByteArray()); return }
            val method = parts[0]
            val path = parts[1]

            val headers = HashMap<String, String>()
            while (true) {
                val line = readLineFromStream(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            // Validate HMAC for protected endpoints (skip for /health, OPTIONS)
            val needsAuth = when {
                method == "POST" && (path == "/print" || path == "/print-ticket") -> true
                method == "GET" && path == "/status" -> true
                else -> false
            }

            if (needsAuth && !validateHmac(headers, ByteArray(0))) {
                writeResponse(output, 401, "application/json", """{"detail":"Invalid or missing HMAC signature"}""".toByteArray(), cors = true)
                return
            }

            when {
                method == "GET" && path == "/health" -> {
                    // APK-compatible format
                    val body = """{"ok":true,"name":"bc86ac-bridge-app","usbPaired":${getPairedUsbDevice() != null}}"""
                    writeResponse(output, 200, "application/json", body.toByteArray())
                }

                method == "OPTIONS" -> {
                    writeCorsPreflight(output)
                }

                method == "POST" && path == "/print" -> {
                    handlePrintRequest(input, output, headers, isCompat)
                }

                method == "POST" && path == "/print-ticket" && isCompat -> {
                    handlePrintTicketRequest(input, output, headers)
                }

                method == "GET" && path == "/status" -> {
                    handleStatusRequest(output)
                }

                else -> writeResponse(output, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (_: Exception) {
            // client disconnected mid-request, etc. -- nothing to do
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handlePrintRequest(input: InputStream, output: OutputStream, headers: Map<String, String>, isCompat: Boolean) {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = readExactly(input, len)

        // Re-validate HMAC with actual body
        if (!validateHmac(headers, body)) {
            writeResponse(output, 401, "application/json", """{"detail":"Invalid HMAC signature"}""".toByteArray(), cors = true)
            return
        }

        val transport = headers["x-transport"] ?: "network"
        val printerHost = headers["x-printer-host"]
        val printerPort = headers["x-printer-port"]?.toIntOrNull() ?: 9100

        try {
            if (transport == "usb" || printerHost.isNullOrBlank()) {
                printOverUsb(body)
            } else {
                printOverNetwork(printerHost, printerPort, body)
            }
            lastPrintAt = System.currentTimeMillis()
            writeResponse(output, 200, "text/plain", "OK".toByteArray(), cors = true)
        } catch (e: Exception) {
            writeResponse(output, 502, "application/json", """{"detail":"Printer error: ${e.message}"}""".toByteArray(), cors = true)
        }
    }

    private fun handlePrintTicketRequest(input: InputStream, output: OutputStream, headers: Map<String, String>) {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = readExactly(input, len)

        // Re-validate HMAC with actual body
        if (!validateHmac(headers, body)) {
            writeResponse(output, 401, "application/json", """{"detail":"Invalid HMAC signature"}""".toByteArray(), cors = true)
            return
        }

        // Parse JSON body: {payload_base64, ticket_number?}
        val jsonStr = String(body, StandardCharsets.UTF_8)
        var payloadB64: String? = null
        var ticketNumber: String? = null

        try {
            val json = org.json.JSONObject(jsonStr)
            payloadB64 = json.optString("payload_base64", null)
            ticketNumber = json.optString("ticket_number", null)
        } catch (_: Exception) {
            writeResponse(output, 400, "application/json", """{"detail":"Invalid JSON"}""".toByteArray(), cors = true)
            return
        }

        if (payloadB64 == null || payloadB64.isBlank()) {
            writeResponse(output, 400, "application/json", """{"detail":"Missing payload_base64"}""".toByteArray(), cors = true)
            return
        }

        val printBytes = try {
            Base64.decode(payloadB64, Base64.DEFAULT)
        } catch (e: Exception) {
            writeResponse(output, 400, "application/json", """{"detail":"Invalid base64 payload"}""".toByteArray(), cors = true)
            return
        }

        if (printBytes.isEmpty()) {
            writeResponse(output, 400, "application/json", """{"detail":"Decoded to zero bytes"}""".toByteArray(), cors = true)
            return
        }

        try {
            // Auto-detect: USB first, LAN fallback
            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val device = getPairedUsbDevice()
            if (device != null && usbManager.hasPermission(device)) {
                printOverUsb(printBytes)
            } else {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val ip = prefs.getString("printer_ip", "192.168.18.100")!!
                val port = prefs.getString("printer_port", "9100")!!.toIntOrNull() ?: 9100
                printOverNetwork(ip, port, printBytes)
            }
            lastPrintAt = System.currentTimeMillis()
            writeResponse(output, 200, "application/json", """{"success":true,"path":"${if (getPairedUsbDevice() != null && usbManager.hasPermission(getPairedUsbDevice()!!)) "usb" else "lan"}"}""".toByteArray(), cors = true)
        } catch (e: Exception) {
            writeResponse(output, 502, "application/json", """{"detail":"Printer error: ${e.message}"}""".toByteArray(), cors = true)
        }
    }

    private fun handleStatusRequest(output: OutputStream) {
        // Return detailed status (requires auth via HMAC)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ip = prefs.getString("printer_ip", "192.168.18.100")!!
        val port = prefs.getString("printer_port", "9100")!!.toIntOrNull() ?: 9100
        val paired = getPairedUsbDevice()
        val usbOk = paired != null && (getSystemService(Context.USB_SERVICE) as UsbManager).hasPermission(paired)

        val uptimeSec = (System.currentTimeMillis() - Process.myStartTime()) / 1000

        val body = """{
            "status":"online",
            "version":"4.0.0",
            "uptime_seconds":$uptimeSec,
            "printer":"$ip",
            "printer_port":$port,
            "printer_reachable":${PrinterBridge.checkPrinterReachable(this, ip, port)},
            "usb_connected":$usbOk,
            "active_print_path":"${if (usbOk) "usb" else "lan"}",
            "last_printed_job_id":null,
            "last_printed_via":"${if (usbOk) "usb" else "lan"}",
            "last_error":${lastError?.let { "\"$it\"" } ?: "null"},
            "jobs_processed":0,
            "jobs_failed":0
        }""".trimIndent()
        writeResponse(output, 200, "application/json", body.toByteArray(), cors = true)
    }

    /**
     * Reads a single CRLF- or LF-terminated line one byte at a time. This is
     * deliberately NOT a BufferedReader: a buffered reader pulls ahead into
     * its own internal buffer, which would silently swallow the first chunk
     * of the raw binary body that follows the headers. Reading byte-by-byte
     * guarantees the stream is left positioned exactly at the first body
     * byte once the blank line after headers is reached.
     */
    private fun readLineFromStream(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) buf.write(b)
            b = input.read()
        }
        return buf.toString("UTF-8")
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) break
            read += n
        }
        return buf
    }

    private fun writeResponse(out: OutputStream, code: Int, contentType: String, body: ByteArray, cors: Boolean = true) {
        val statusText = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 502 -> "Bad Gateway"; else -> "Error" }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $statusText\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        if (cors) {
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("Access-Control-Allow-Headers: Content-Type, X-Printer-Host, X-Printer-Port, X-Transport, X-Print-Secret, X-Print-Ts, X-Print-Sig, X-Print-Nonce, X-Bridge-Secret, X-Bridge-Ts, X-Bridge-Sig, X-Bridge-Nonce\r\n")
            sb.append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
        }
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun writeCorsPreflight(out: OutputStream) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 204 No Content\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, X-Printer-Host, X-Printer-Port, X-Transport, X-Print-Secret, X-Print-Ts, X-Print-Sig, X-Print-Nonce, X-Bridge-Secret, X-Bridge-Ts, X-Bridge-Sig, X-Bridge-Nonce\r\n")
        sb.append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
        sb.append("Content-Length: 0\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    // ---- Network printing ----

    fun printOverNetwork(host: String, port: Int, bytes: ByteArray) {
        PrinterBridge.printOverNetworkWithRetry(host, port, bytes)
    }

    // ---- USB printing ----

    fun getPairedUsbDevice(): UsbDevice? = PrinterBridge.getPairedUsbDevice(this)

    fun printOverUsb(bytes: ByteArray) {
        PrinterBridge.printOverUsb(this, bytes)
    }

    // ---- Notification ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Print Bridge", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the BC-86AC print bridge running in the background" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BC-86AC Print Bridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun autoRequestUsbPermission() {
        val device = PrinterBridge.getPairedUsbDevice(this) ?: return
        if (usbManager.hasPermission(device)) return

        Log.d(TAG, "Auto-requesting USB permission for ${device.productName ?: device.deviceName}")

        val filter = android.content.IntentFilter(ACTION_USB_PERMISSION)
        usbPermissionReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB permission result: granted=$granted")
                    try { unregisterReceiver(this) } catch (_: Exception) {}
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }

        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, permissionIntent)
    }
}
