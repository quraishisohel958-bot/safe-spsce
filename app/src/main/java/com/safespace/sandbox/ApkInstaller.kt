package com.safespace.sandbox

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Silent install/uninstall INSIDE the Alternative Space (work profile).
 * Profile Owner hone ke karan Android ise allow karta hai — mods bina
 * system installer ke direct space me install ho jate hain.
 * Agar koi OEM user-action maange, to system ka confirm screen fallback me khulta hai.
 */
object ApkInstaller {

    private const val ACTION = "com.safespace.sandbox.INSTALL_STATUS"

    fun install(context: Context, apk: File, onResult: (ok: Boolean, msg: String) -> Unit) {
        val installer = context.packageManager.packageInstaller
        // Profile Owner ke liye Android khud silent install allow karta hai.
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                handleStatus(context, i, onResult)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )

        try {
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            apk.inputStream().use { ins ->
                session.openWrite("base", 0, -1).use { out ->
                    ins.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ACTION).setPackage(context.packageName)
            val sender = PendingIntent.getBroadcast(
                context, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(sender.intentSender)
            session.close()
        } catch (e: Exception) {
            onResult(false, "Install error: ${e.message}")
        }
    }

    fun uninstall(context: Context, pkg: String, onResult: (ok: Boolean, msg: String) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (st == PackageInstaller.STATUS_SUCCESS) {
                    onResult(true, "Mod uninstall ho gaya ✅")
                } else {
                    onResult(false, "Uninstall fail: " +
                        (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown"))
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val intent = Intent(ACTION).setPackage(context.packageName)
            val sender = PendingIntent.getBroadcast(
                context, pkg.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            installer.uninstall(pkg, sender.intentSender)
        } catch (e: Exception) {
            onResult(false, "Uninstall error: ${e.message}")
        }
    }

    private fun handleStatus(context: Context, i: Intent, onResult: (Boolean, String) -> Unit) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> onResult(true, "Mod space me install ho gaya ✅")

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    try {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(confirm)
                        onResult(false, "Phone confirm maang raha hai — wahan Install dabao.")
                    } catch (e: Exception) {
                        onResult(false, "Confirm screen nahi khul: ${e.message}")
                    }
                } else {
                    onResult(false, "User action needed, par intent nahi mila")
                }
            }

            else -> onResult(
                false,
                "Install fail: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Unknown error")
            )
        }
    }
}
