package com.bc86ac.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
        const val PORT = 9876
        const val CHANNEL_ID = "bc86ac_bridge_channel"
        const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "bc86ac_bridge_prefs"
        const val PREF_USB_VENDOR_ID = "usb_vendor_id"
        const val PREF_USB_PRODUCT_ID = "usb_product_id"

        @Volatile var isRunning = false
            private set

        @Volatile var lastError: String? = null
            private set

        @Volatile var lastPrintAt: Long = 0
            private set
    }

    private lateinit var serverSocket: ServerSocket
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private lateinit var usbManager: UsbManager
    private lateinit var prefs: SharedPreferences
    private lateinit var poller: SupabasePoller

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
        }
        poller.start()
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        isRunning = false
        poller.stop()
        try { if (::serverSocket.isInitialized) serverSocket.close() } catch (_: Exception) {}
        pool.shutdownNow()
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

    private fun handleClient(socket: Socket) {
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

            when {
                method == "GET" && path == "/health" -> {
                    val body = """{"ok":true,"name":"bc86ac-bridge-app","usbPaired":${getPairedUsbDevice() != null}}"""
                    writeResponse(output, 200, "application/json", body.toByteArray())
                }

                method == "OPTIONS" -> {
                    writeCorsPreflight(output)
                }

                method == "POST" && path == "/print" -> {
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = readExactly(input, len)
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
                        writeResponse(output, 502, "text/plain", "Printer error: ${e.message}".toByteArray(), cors = true)
                    }
                }

                else -> writeResponse(output, 404, "text/plain", "Not found".toByteArray())
            }
        } catch (_: Exception) {
            // client disconnected mid-request, etc. -- nothing to do
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
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
            sb.append("Access-Control-Allow-Headers: Content-Type, X-Printer-Host, X-Printer-Port, X-Transport\r\n")
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
        sb.append("Access-Control-Allow-Headers: Content-Type, X-Printer-Host, X-Printer-Port, X-Transport\r\n")
        sb.append("Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n")
        sb.append("Content-Length: 0\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()
    }

    // ---- Network printing ----

    fun printOverNetwork(host: String, port: Int, bytes: ByteArray) {
        PrinterBridge.printOverNetwork(host, port, bytes)
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
}
