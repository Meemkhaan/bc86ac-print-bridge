package com.bc86ac.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * Receives the result of PackageInstaller.Session.commit() so we can
 * tell the user whether the update installed successfully.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // User needs to confirm the install -- launch the confirmation
                val confirmIntent = intent.getParcelableExtra<Intent>(
                    Intent.EXTRA_INTENT
                )
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, "Update installed! Restart the app.", Toast.LENGTH_LONG).show()
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.e(TAG, "Install failed: status=$status, msg=$message")
                Toast.makeText(context, "Install failed: $message", Toast.LENGTH_LONG).show()
            }
            else -> {
                Log.w(TAG, "Unknown install status: $status")
            }
        }
    }

    companion object {
        private const val TAG = "InstallResult"
    }
}
