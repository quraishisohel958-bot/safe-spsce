package com.safespace.sandbox

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * APK ka offline security scanner:
 *  - khatarnak permissions ko weight ke saath score karta hai
 *  - spyware / SMS-fraud patterns detect karta hai
 *  - file ka SHA-256 nikaalta hai (VirusTotal check ke liye)
 */
object ApkAnalyzer {

    const val INTERNET = "android.permission.INTERNET"

    data class Finding(val title: String, val detail: String, val weight: Int)

    enum class Level { LOW, MEDIUM, HIGH, CRITICAL }

    data class Report(
        val ok: Boolean,
        val error: String? = null,
        val appName: String = "?",
        val packageName: String = "?",
        val versionName: String = "?",
        val sizeBytes: Long = 0,
        val sha256: String = "",
        val targetSdk: Int = 0,
        val minSdk: Int = 0,
        val requested: List<String> = emptyList(),
        val findings: List<Finding> = emptyList(),
        val score: Int = 0
    ) {
        val level: Level
            get() = when {
                score >= 40 -> Level.CRITICAL
                score >= 22 -> Level.HIGH
                score >= 10 -> Level.MEDIUM
                else -> Level.LOW
            }
    }

    private val PERM_RISK: Map<String, Pair<Int, String>> = mapOf(
        "android.permission.READ_SMS" to Pair(12, "Aapke saare SMS padh sakta hai — OTP chori ka sabse bada risk"),
        "android.permission.SEND_SMS" to Pair(12, "Aapke number se (premium) SMS bhej sakta hai — balance kat sakta hai"),
        "android.permission.RECEIVE_SMS" to Pair(10, "Aane wale SMS/OTP intercept kar sakta hai"),
        "android.permission.RECEIVE_MMS" to Pair(6, "MMS messages padh sakta hai"),
        "android.permission.RECEIVE_WAP_PUSH" to Pair(4, "Push messages padh sakta hai"),
        "android.permission.READ_CALL_LOG" to Pair(10, "Puri call history upload ho sakti hai"),
        "android.permission.WRITE_CALL_LOG" to Pair(6, "Call log badal sakta hai (fraud chhupane ke liye)"),
        "android.permission.PROCESS_OUTGOING_CALLS" to Pair(10, "Aapke outgoing calls intercept kar sakta hai"),
        "android.permission.READ_PHONE_STATE" to Pair(8, "IMEI / phone number jaisi info nikaal sakta hai"),
        "android.permission.CALL_PHONE" to Pair(6, "Bina puche call kar sakta hai (premium numbers)"),
        "android.permission.ANSWER_PHONE_CALLS" to Pair(3, "Calls auto-answer kar sakta hai"),
        "android.permission.READ_PHONE_NUMBERS" to Pair(3, "Phone numbers padh sakta hai"),
        "android.permission.READ_CONTACTS" to Pair(10, "Contacts chura kar spam/upload kar sakta hai"),
        "android.permission.WRITE_CONTACTS" to Pair(6, "Contacts badal sakta hai"),
        "android.permission.GET_ACCOUNTS" to Pair(5, "Phone par bane accounts ki list nikaal sakta hai"),
        "android.permission.RECORD_AUDIO" to Pair(8, "Microphone se secretly sun sakta hai"),
        "android.permission.CAMERA" to Pair(5, "Camera use kar sakta hai"),
        "android.permission.ACCESS_FINE_LOCATION" to Pair(6, "Exact location track kar sakta hai"),
        "android.permission.ACCESS_COARSE_LOCATION" to Pair(3, "Approx location track kar sakta hai"),
        "android.permission.ACCESS_BACKGROUND_LOCATION" to Pair(10, "App band hone par bhi location track kar sakta hai"),
        "android.permission.REQUEST_INSTALL_PACKAGES" to Pair(12, "Khud aur apps install kar sakta hai — malware dropper ka common pattern"),
        "android.permission.SYSTEM_ALERT_WINDOW" to Pair(8, "Screen ke upar fake windows dikha kar phishing kar sakta hai"),
        "android.permission.QUERY_ALL_PACKAGES" to Pair(5, "Phone ki saari installed apps dekh sakta hai"),
        "android.permission.RECEIVE_BOOT_COMPLETED" to Pair(4, "Phone start hote hi khud chalu ho jayega"),
        "android.permission.MANAGE_EXTERNAL_STORAGE" to Pair(8, "Poora storage padh/likh sakta hai (photos, files sab)"),
        "android.permission.WRITE_EXTERNAL_STORAGE" to Pair(2, "Storage me likh sakta hai"),
        "android.permission.READ_EXTERNAL_STORAGE" to Pair(2, "Storage ki files padh sakta hai"),
        "android.permission.READ_MEDIA_IMAGES" to Pair(2, "Aapki photos padh sakta hai"),
        "android.permission.READ_MEDIA_VIDEO" to Pair(2, "Aapke videos padh sakta hai"),
        "android.permission.BODY_SENSORS" to Pair(4, "Health sensors ka data nikaal sakta hai"),
        "android.permission.ACTIVITY_RECOGNITION" to Pair(2, "Physical activity track kar sakta hai"),
        "android.permission.PACKAGE_USAGE_STATS" to Pair(4, "Kab kaunsa app use karte ho, track kar sakta hai"),
        "android.permission.BIND_DEVICE_ADMIN" to Pair(10, "Device admin ban kar uninstall rok sakta hai")
    )

    fun analyze(pm: PackageManager, apk: File): Report {
        val sha = sha256(apk) ?: ""
        val pi: PackageInfo? = try {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
            )
        } catch (_: Exception) {
            null
        }
        if (pi == null || pi.applicationInfo == null) {
            return Report(
                ok = false,
                error = "APK file padhi nahi ja saka — corrupt ya galat file ho sakti hai.",
                sizeBytes = apk.length(),
                sha256 = sha
            )
        }
        pi.applicationInfo.sourceDir = apk.absolutePath
        pi.applicationInfo.publicSourceDir = apk.absolutePath

        val pkg = pi.packageName ?: "?"
        val appName = try {
            pi.applicationInfo.loadLabel(pm).toString()
        } catch (_: Exception) {
            pkg
        }

        val perms: List<String> = pi.requestedPermissions?.toList() ?: emptyList()
        val findings = mutableListOf<Finding>()
        var score = 0
        val hasInternet = perms.contains(INTERNET)

        for (p in perms) {
            val r = PERM_RISK[p] ?: continue
            score += r.first
            findings.add(Finding(p.substringAfterLast('.'), r.second, r.first))
        }

        // Accessibility service = spyware ka sabse common tool
        val accService = pi.services?.any {
            it.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE"
        } == true
        if (accService) {
            findings.add(
                Finding(
                    "ACCESSIBILITY SERVICE",
                    "Yeh app aapki screen ka har text padh sakta hai — passwords/OTP included. Spyware ka sabse common hathiyar.",
                    16
                )
            )
            score += 16
            if (hasInternet) {
                findings.add(
                    Finding(
                        "SPYWARE PATTERN",
                        "Accessibility + Internet: screen padhkar data bahar bhej sakta hai. Install na karna behtar hai.",
                        12
                    )
                )
                score += 12
            }
        }

        // SMS fraud pattern
        val smsish = perms.count { it.contains("SMS") || it.contains("CALL_LOG") }
        if (smsish >= 2 && hasInternet) {
            findings.add(
                Finding(
                    "SMS FRAUD PATTERN",
                    "SMS permissions + Internet: premium-SMS fraud ya OTP chori ka classic pattern.",
                    10
                )
            )
            score += 10
        }

        if (pi.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            findings.add(Finding("Debug build", "App debug mode me bana hai — casually banaaya hua package.", 4))
            score += 4
        }
        val parts = pkg.split('.')
        if (parts.size >= 3 && parts.any { it.length <= 2 }) {
            findings.add(Finding("Banaavati package name", "Package name jaan-boojh kar chhota/gupt hai (jaise a.b.c).", 4))
            score += 4
        }
        if (pi.applicationInfo.targetSdkVersion in 1..22) {
            findings.add(Finding("Bahut purana target SDK", "App naye Android ke security rules bypass karti hai.", 4))
            score += 4
        }

        return Report(
            ok = true,
            appName = appName,
            packageName = pkg,
            versionName = pi.versionName ?: "?",
            sizeBytes = apk.length(),
            sha256 = sha,
            targetSdk = pi.applicationInfo.targetSdkVersion,
            minSdk = pi.applicationInfo.minSdkVersion,
            requested = perms.map { it.substringAfterLast('.') },
            findings = findings.sortedByDescending { it.weight },
            score = score
        )
    }

    fun sha256(f: File): String? = try {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(65536)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                d.update(buf, 0, n)
            }
        }
        d.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }
}
