package com.bc86ac.bridge

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * USB + network print I/O, independent of the Service/Activity lifecycle so
 * it can be safely called from either (the HTTP server inside
 * PrintBridgeService, or the manual test buttons in MainActivity).
 */
object PrinterBridge {

    private const val TAG = "PrinterBridge"
    private const val MAX_NETWORK_RETRIES = 3

    fun getPairedUsbDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val prefs = context.getSharedPreferences(PrintBridgeService.PREFS_NAME, Context.MODE_PRIVATE)
        val vendorId = prefs.getInt(PrintBridgeService.PREF_USB_VENDOR_ID, -1)
        val productId = prefs.getInt(PrintBridgeService.PREF_USB_PRODUCT_ID, -1)
        if (vendorId == -1) return null
        return usbManager.deviceList.values.find {
            it.vendorId == vendorId && it.productId == productId
        }
    }

    fun printAuto(context: Context, bytes: ByteArray) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = getPairedUsbDevice(context)
        Log.d(TAG, "printAuto: ${bytes.size} bytes, USB device=${device?.productName}, hasPermission=${device?.let { usbManager.hasPermission(it) }}")
        if (device != null && usbManager.hasPermission(device)) {
            try {
                printOverUsb(context, bytes)
                Log.d(TAG, "printAuto: USB print succeeded")
                return
            } catch (e: Exception) {
                Log.w(TAG, "USB print failed, falling back to network: ${e.message}")
            }
        }
        val prefs = context.getSharedPreferences(PrintBridgeService.PREFS_NAME, Context.MODE_PRIVATE)
        val ip = prefs.getString("printer_ip", "192.168.18.100")!!
        val port = prefs.getString("printer_port", "9100")!!.toIntOrNull() ?: 9100
        Log.d(TAG, "printAuto: printing over network to $ip:$port")
        printOverNetworkWithRetry(ip, port, bytes)
        Log.d(TAG, "printAuto: network print succeeded")
    }

    fun printOverUsb(context: Context, bytes: ByteArray) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = getPairedUsbDevice(context)
            ?: throw IllegalStateException("No USB printer paired. Open the app and pair it first.")

        if (!usbManager.hasPermission(device)) {
            throw IllegalStateException("USB permission not granted for paired device. Open the app to re-grant.")
        }

        val target = findBulkOutInterface(device)
            ?: throw IllegalStateException("Could not find a bulk OUT endpoint on the paired USB device.")

        val connection: UsbDeviceConnection = usbManager.openDevice(device)
            ?: throw IllegalStateException("Could not open USB device.")

        try {
            connection.claimInterface(target.first, true)
            val endpoint = target.second
            var offset = 0
            val chunkSize = 4096
            while (offset < bytes.size) {
                val len = minOf(chunkSize, bytes.size - offset)
                val chunk = bytes.copyOfRange(offset, offset + len)
                val sent = connection.bulkTransfer(endpoint, chunk, chunk.size, 5000)
                if (sent < 0) throw IllegalStateException("USB transfer failed.")
                offset += len
            }
        } finally {
            try { connection.releaseInterface(target.first) } catch (_: Exception) {}
            connection.close()
        }
    }

    fun printOverNetwork(host: String, port: Int, bytes: ByteArray) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 5000)
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            Log.d(TAG, "Network print: sent ${bytes.size} bytes to $host:$port, socket OutputStream flushed")
            Thread.sleep(200) // give the printer a moment before we close
        }
    }

    fun printOverNetworkWithRetry(host: String, port: Int, bytes: ByteArray) {
        var lastException: Exception? = null
        for (attempt in 1..MAX_NETWORK_RETRIES) {
            try {
                Log.d(TAG, "Network print attempt $attempt/$MAX_NETWORK_RETRIES to $host:$port")
                printOverNetwork(host, port, bytes)
                return
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt $attempt failed: ${e.message}")
                if (attempt < MAX_NETWORK_RETRIES) {
                    val delayMs = attempt * 500L
                    Log.d(TAG, "Retrying in ${delayMs}ms...")
                    Thread.sleep(delayMs)
                }
            }
        }
        throw lastException!!
    }

    private fun findBulkOutInterface(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            for (e in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(e)
                if (endpoint.direction == UsbConstants.USB_DIR_OUT && endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    return Pair(iface, endpoint)
                }
            }
        }
        return null
    }
}
