package com.safespace.sandbox

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

/**
 * Device/Profile admin receiver.
 * Jab work profile (Alternative Space) banta hai, system isko call karta hai
 * aur hum profile ki security policies set karte hain.
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AdminReceiver::class.java)
        if (!dpm.isProfileOwnerApp(context.packageName)) return

        try {
            dpm.setProfileName(admin, "Alternative Space")
        } catch (_: Exception) {
        }

        // Space ke andar se koi bhi seedha APK sideload na kar sake —
        // install sirf SafeSpace ke scanner se hoga.
        try {
            dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        } catch (_: Exception) {
        }

        // Personal <-> Work data leakage band karo
        try {
            dpm.setCrossProfileCallerIdDisabled(admin, true)
        } catch (_: Exception) {
        }
        try {
            dpm.setCrossProfileContactsSearchDisabled(admin, true)
        } catch (_: Exception) {
        }

        // Apna icon space ke andar visible rakho
        try {
            dpm.setApplicationHidden(admin, context.packageName, false)
        } catch (_: Exception) {
        }
    }
}
