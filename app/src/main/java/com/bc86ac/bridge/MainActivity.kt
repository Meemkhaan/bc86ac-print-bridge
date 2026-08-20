package com.bc86ac.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bc86ac.bridge.databinding.ActivityMainBinding
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var usbManager: UsbManager
    private lateinit var prefs: android.content.SharedPreferences
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val ACTION_USB_PERMISSION = "com.bc86ac.bridge.USB_PERMISSION"

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        prefs.edit()
                            .putInt(PrintBridgeService.PREF_USB_VENDOR_ID, device.vendorId)
                            .putInt(PrintBridgeService.PREF_USB_PRODUCT_ID, device.productId)
                            .apply()
                        toast("Paired: ${device.productName ?: "USB printer"}")
                    } else {
                        toast("USB permission denied")
                    }
                    refreshStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        prefs = getSharedPreferences(PrintBridgeService.PREFS_NAME, Context.MODE_PRIVATE)

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }

        binding.startServiceBtn.setOnClickListener { startBridgeService() }
        binding.stopServiceBtn.setOnClickListener { stopBridgeService() }
        binding.pairUsbBtn.setOnClickListener { pickAndPairUsbDevice() }
        binding.testUsbBtn.setOnClickListener { testPrint(useUsb = true) }
        binding.testNetworkBtn.setOnClickListener { testPrint(useUsb = false) }
        binding.saveNetworkBtn.setOnClickListener { saveNetworkConfig() }
        binding.batteryOptBtn.setOnClickListener { requestIgnoreBatteryOptimizations() }
        binding.saveCloudBtn.setOnClickListener { saveCloudConfig() }

        loadNetworkConfig()
        loadCloudConfig()
        requestNotificationPermissionIfNeeded()
        startBridgeService() // auto-start on app open; also runs on boot via BootReceiver
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        mainHandler.post(statusRefreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(statusRefreshRunnable)
    }

    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, 2000)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(usbPermissionReceiver) } catch (_: Exception) {}
    }

    // ---- Service control ----

    private fun startBridgeService() {
        val intent = Intent(this, PrintBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
        mainHandler.postDelayed({ refreshStatus() }, 500)
    }

    private fun stopBridgeService() {
        stopService(Intent(this, PrintBridgeService::class.java))
        mainHandler.postDelayed({ refreshStatus() }, 300)
    }

    private fun refreshStatus() {
        val running = PrintBridgeService.isRunning
        binding.serviceStatusText.text = if (running) {
            "Running on port ${PrintBridgeService.PORT}"
        } else {
            "Stopped" + (PrintBridgeService.lastError?.let { " ($it)" } ?: "")
        }

        val ip = getLocalIpAddress()
        binding.lanAddressText.text = if (ip != null && running) {
            "Reachable at: http://$ip:${PrintBridgeService.PORT}"
        } else if (ip != null) {
            "Device IP: $ip (service stopped)"
        } else {
            "Not connected to Wi-Fi"
        }

        val paired = getPairedDeviceInfo()
        binding.usbStatusText.text = paired ?: "No USB printer paired"

        binding.cloudSyncStatusText.text = getCloudSyncStatusText()
    }

    private fun getCloudSyncStatusText(): String {
        val url = prefs.getString("supabase_url", null)
        if (url.isNullOrBlank()) return "Not configured"

        val lastPoll = SupabasePoller.lastPollAt
        val err = SupabasePoller.lastError
        if (lastPoll == 0L) return "Configured, waiting for first poll..."

        val secondsAgo = (System.currentTimeMillis() - lastPoll) / 1000
        return if (err != null) {
            "Error: $err (last tried ${secondsAgo}s ago)"
        } else {
            "Connected, last checked ${secondsAgo}s ago"
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var fallback: String? = null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("100.")) {
                            if (fallback == null) fallback = ip
                            continue
                        }
                        return ip
                    }
                }
            }
            fallback
        } catch (_: Exception) { null }
    }

    // ---- USB pairing ----

    private fun pickAndPairUsbDevice() {
        val devices = usbManager.deviceList.values.toList()
        if (devices.isEmpty()) {
            toast("No USB device detected -- check the cable/adapter")
            return
        }
        // If there's exactly one device, pair it directly. If there are
        // several, pair the first non-hub-looking one -- most tablets only
        // have one external USB device attached at a time anyway.
        val device = devices.first()
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun getPairedDeviceInfo(): String? {
        val vendorId = prefs.getInt(PrintBridgeService.PREF_USB_VENDOR_ID, -1)
        if (vendorId == -1) return null
        val productId = prefs.getInt(PrintBridgeService.PREF_USB_PRODUCT_ID, -1)
        val device = usbManager.deviceList.values.find { it.vendorId == vendorId && it.productId == productId }
        return if (device != null) {
            val hasPermission = usbManager.hasPermission(device)
            "Paired: ${device.productName ?: "USB device"} (${if (hasPermission) "permission OK" else "needs re-pair"})"
        } else {
            "Paired (not currently connected)"
        }
    }

    // ---- Network config ----

    private fun loadNetworkConfig() {
        binding.printerIpInput.setText(prefs.getString("printer_ip", "192.168.18.100"))
        binding.printerPortInput.setText(prefs.getString("printer_port", "9100"))
    }

    private fun saveNetworkConfig() {
        prefs.edit()
            .putString("printer_ip", binding.printerIpInput.text.toString().trim())
            .putString("printer_port", binding.printerPortInput.text.toString().trim())
            .apply()
        toast("Saved")
    }

    // ---- Cloud sync (Supabase) config ----

    private fun loadCloudConfig() {
        binding.supabaseUrlInput.setText(prefs.getString("supabase_url", ""))
        binding.supabaseKeyInput.setText(prefs.getString("supabase_anon_key", ""))
    }

    private fun saveCloudConfig() {
        prefs.edit()
            .putString("supabase_url", binding.supabaseUrlInput.text.toString().trim())
            .putString("supabase_anon_key", binding.supabaseKeyInput.text.toString().trim())
            .apply()
        toast("Saved -- polling will pick up the new config within a few seconds")
    }

    // ---- Test prints ----

    private fun testPrint(useUsb: Boolean) {
        executor.execute {
            try {
                val bytes = buildTestPage()
                if (useUsb) {
                    PrinterBridge.printOverUsb(applicationContext, bytes)
                } else {
                    val ip = prefs.getString("printer_ip", "192.168.18.100")!!
                    val port = prefs.getString("printer_port", "9100")!!.toIntOrNull() ?: 9100
                    PrinterBridge.printOverNetwork(ip, port, bytes)
                }
                mainHandler.post { toast("Sent to printer") }
            } catch (e: Exception) {
                mainHandler.post { toast("Print failed: ${e.message}") }
            }
        }
    }

    // ---- Permissions / battery ----

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            toast("Open Settings > Apps > BC-86AC Print Bridge > Battery manually")
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
