package com.bc86ac.bridge

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer build and lets the user install it
 * directly from the tablet -- no电脑 needed.
 *
 * Workflow: GitHub Actions builds the APK → creates a Release tagged
 * build-N → attaches the APK as a release asset. This class hits the
 * GitHub API to find the latest release, compares versionCode, and
 * triggers PackageInstaller if there's a newer build.
 */
object AppUpdater {

    private const val TAG = "AppUpdater"
    private const val REPO = "Meemkhaan/bc86ac-print-bridge"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val downloadUrl: String,
        val releaseName: String
    )

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) { 0 }
    }

    fun checkForUpdate(context: Context): UpdateInfo? {
        try {
            val url = URL(API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GitHub API returned $code")
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = org.json.JSONObject(body)

            val tagName = json.optString("tag_name", "")
            val releaseName = json.optString("name", tagName)
            val versionCode = tagName.removePrefix("build-").toIntOrNull() ?: 0

            if (versionCode <= getCurrentVersionCode(context)) {
                Log.d(TAG, "Already up to date (local=${getCurrentVersionCode(context)}, remote=$versionCode)")
                return null
            }

            val assets = json.optJSONArray("assets") ?: return null
            val apkAsset = findApkAsset(assets) ?: return null
            val downloadUrl = apkAsset.getString("browser_download_url")

            Log.d(TAG, "Update available: $releaseName (v$versionCode)")
            return UpdateInfo(versionCode, "Build $versionCode", downloadUrl, releaseName)
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            return null
        }
    }

    private fun findApkAsset(assets: JSONArray): org.json.JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                return asset
            }
        }
        return null
    }

    fun downloadAndInstall(context: Context, update: UpdateInfo, onProgress: ((String) -> Unit)? = null) {
        try {
            onProgress?.invoke("Downloading ${update.releaseName}...")

            val url = URL(update.downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000

            val code = conn.responseCode
            if (code !in 200..299) {
                onProgress?.invoke("Download failed: HTTP $code")
                return
            }

            val apkFile = File(context.cacheDir, "update-${update.versionCode}.apk")
            apkFile.outputStream().use { out ->
                conn.inputStream.use { inp ->
                    inp.copyTo(out)
                }
            }
            conn.disconnect()

            onProgress?.invoke("Installing...")
            installApk(context, apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed: ${e.message}", e)
            onProgress?.invoke("Failed: ${e.message}")
        }
    }

    private fun installApk(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val sessionParams = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(sessionParams)

        val session = packageInstaller.openSession(sessionId)
        session.openWrite("apk", 0, apkFile.length()).use { out ->
            apkFile.inputStream().use { inp ->
                inp.copyTo(out)
            }
            session.fsync(out)
        }

        val intent = Intent(context, InstallResultReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        session.commit(pendingIntent.intentSender)
    }
}
