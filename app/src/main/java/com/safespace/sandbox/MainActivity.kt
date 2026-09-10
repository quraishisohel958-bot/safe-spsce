package com.safespace.sandbox

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.CrossProfileApps
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.text.format.Formatter
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.safespace.sandbox.ApkAnalyzer.Report
import com.safespace.sandbox.databinding.ActivityMainBinding
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var um: UserManager
    private lateinit var pm: PackageManager
    private lateinit var prefs: SharedPreferences
    private val exec = Executors.newSingleThreadExecutor()
    private var adapter: AppsAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, AdminReceiver::class.java)
        um = getSystemService(Context.USER_SERVICE) as UserManager
        pm = packageManager
        prefs = getSharedPreferences("safespace", Context.MODE_PRIVATE)

        b.btnProvision.setOnClickListener { startProvisioning() }
        b.btnRecheck.setOnClickListener { route() }
        b.btnOpenSpace.setOnClickListener { openSpace() }
        b.btnWipe.setOnClickListener { confirmWipe() }
        b.btnPickApk.setOnClickListener { pickApk() }

        b.switchStrict.isChecked = prefs.getBoolean("strict", true)
        b.switchStrict.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("strict", checked).apply()
            applyStrictToInstalled(checked)
            Toast.makeText(
                this,
                if (checked) "Strict ON — camera/mic/location bhi block"
                else "Strict OFF — basic spyware-block chalu hai",
                Toast.LENGTH_LONG
            ).show()
        }

        b.recyclerApps.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        route()
    }

    private fun route() {
        val isPo = dpm.isProfileOwnerApp(packageName)
        val inProfile = isPo && um.isManagedProfile
        val managedMain = isPo && !um.isManagedProfile

        b.scrollArea.visibility = if (!isPo || managedMain) View.VISIBLE else View.GONE
        b.viewOnboard.visibility = if (!isPo) View.VISIBLE else View.GONE
        b.viewManaged.visibility = if (managedMain) View.VISIBLE else View.GONE
        b.spaceArea.visibility = if (inProfile) View.VISIBLE else View.GONE

        if (inProfile) refreshList()
    }

    // ---------------- Onboarding (personal side, owner nahi bana abhi) ----------------

    private fun startProvisioning() {
        if (!dpm.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            msgDialog(
                "Setup possible nahi hai",
                "Is phone par work profile (Alternative Space) allowed nahi hai.\n\n" +
                    "Ho sakta hai: (1) phone me pehle se koi work profile ho — Settings me check karo, " +
                    "(2) ya aapka phone brand (kuch MIUI/HyperOS models) ise block karta hai."
            )
            return
        }
        val i = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        i.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
        if (Build.VERSION.SDK_INT >= 29) {
            i.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOW_OFFLINE, true)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            msgDialog("Setup start nahi hua", "Phone ne provisioning block kar di.\nDetail: ${e.message}")
        }
    }

    // ---------------- Managed main instance (space ban chuka hai) ----------------

    private fun openSpace() {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val cpa = getSystemService(Context.CROSS_PROFILE_APPS_SERVICE) as CrossProfileApps
                val targets = cpa.targetUserProfiles
                if (targets.isNotEmpty()) {
                    cpa.startMainActivity(ComponentName(this, MainActivity::class.java), targets[0])
                    return
                }
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "Launcher me 💼 Work badge wala SafeSpace icon kholo", Toast.LENGTH_LONG).show()
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle("Space delete karein?")
            .setMessage(
                "Alternative Space aur uske saare mods + unka data hamesha ke liye delete ho jayenge. " +
                    "Aapke personal data par koi asar nahi padega."
            )
            .setPositiveButton("Haan, delete") { _, _ ->
                try {
                    dpm.wipeData(0)
                } catch (e: Exception) {
                    Toast.makeText(this, "Wipe fail: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- Space instance (work profile ke andar) ----------------

    private fun pickApk() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/vnd.android.package-archive",
                    "application/octet-stream",
                    "application/zip"
                )
            )
        }
        try {
            startActivityForResult(i, 42)
        } catch (e: Exception) {
            Toast.makeText(this, "File picker nahi khula: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 42 && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            analyzePicked(uri)
        }
    }

    private fun analyzePicked(uri: Uri) {
        Toast.makeText(this, "APK scan ho raha hai…", Toast.LENGTH_SHORT).show()
        exec.execute {
            val tmp = File(cacheDir, "picked_${System.currentTimeMillis()}.apk")
            try {
                contentResolver.openInputStream(uri)!!.use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                }
            } catch (e: Exception) {
                tmp.delete()
                runOnUiThread {
                    Toast.makeText(this, "APK copy nahi hui: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@execute
            }
            val report = ApkAnalyzer.analyze(pm, tmp)
            runOnUiThread {
                if (!report.ok) {
                    tmp.delete()
                    Toast.makeText(this, report.error ?: "APK invalid", Toast.LENGTH_LONG).show()
                } else {
                    showReport(report, tmp)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showReport(r: Report, apk: File) {
        val ctx = this
        val scroll = ScrollView(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        fun tv(
            text: String,
            size: Float = 14f,
            bold: Boolean = false,
            color: Int = 0xFF212121.toInt(),
            top: Int = 4
        ): TextView {
            val t = TextView(ctx)
            t.text = text
            t.textSize = size
            t.setTextColor(color)
            t.setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(top)
            box.addView(t, lp)
            return t
        }

        val (levelTxt, levelColor) = when (r.level) {
            ApkAnalyzer.Level.LOW -> "🟢 RISK: LOW — lagbhag safe" to 0xFF2E7D32.toInt()
            ApkAnalyzer.Level.MEDIUM -> "🟡 RISK: MEDIUM — samajh ke install karo" to 0xFFF9A825.toInt()
            ApkAnalyzer.Level.HIGH -> "🟠 RISK: HIGH — install karna risky hai" to 0xFFEF6C00.toInt()
            ApkAnalyzer.Level.CRITICAL -> "🔴 RISK: CRITICAL — spyware jaisa behave kar sakta hai" to 0xFFC62828.toInt()
        }

        tv(r.appName, 20f, true)
        tv(r.packageName, 12f, color = 0xFF666666.toInt())
        tv(
            "Version ${r.versionName} • ${Formatter.formatFileSize(ctx, r.sizeBytes)} • targetSDK ${r.targetSdk}",
            12f, color = 0xFF666666.toInt(), top = 2
        )
        tv(levelTxt, 15f, true, levelColor, top = 10)
        tv("SHA-256: ${r.sha256.take(28)}…", 11f, color = 0xFF666666.toInt(), top = 8)

        val vt = Button(ctx)
        vt.text = "🔍 VirusTotal par check karo (recommended)"
        vt.backgroundTintList = ColorStateList.valueOf(0xFFEEEEEE.toInt())
        vt.setTextColor(0xFF333333.toInt())
        vt.setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.virustotal.com/gui/file/${r.sha256}"))
                )
            } catch (e: Exception) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("sha256", r.sha256))
                Toast.makeText(
                    ctx,
                    "Browser nahi mila — SHA-256 copy kar liya. Kisi bhi browser me virustotal.com kholo.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        box.addView(vt)

        tv("Findings (${r.findings.size}):", 14f, true, top = 12)
        if (r.findings.isEmpty()) {
            tv("• Koi khatarnak cheez nahi mili 🎉", 13f)
        } else {
            for (f in r.findings) {
                tv("⚠️ ${f.title} (+${f.weight})\n   ${f.detail}", 13f, top = 6)
            }
        }

        scroll.addView(box)
        AlertDialog.Builder(ctx)
            .setTitle("🛡️ Security Report")
            .setView(scroll)
            .setPositiveButton(
                if (r.level == ApkAnalyzer.Level.CRITICAL) "Phir bhi install karo (risk meri zimmedari)"
                else "Space me install karo ✅"
            ) { _, _ -> doInstall(apk, r) }
            .setNegativeButton("Cancel") { _, _ -> apk.delete() }
            .setOnCancelListener { apk.delete() }
            .show()
    }

    private fun doInstall(apk: File, r: Report) {
        Toast.makeText(this, "Install ho raha hai…", Toast.LENGTH_SHORT).show()
        ApkInstaller.install(this, apk) { ok, msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) {
                    applyDenial(r.packageName, prefs.getBoolean("strict", true))
                    refreshList()
                }
                apk.delete()
            }
        }
    }

    private fun applyDenial(pkg: String, strict: Boolean) {
        val base = listOf(
            "android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS",
            "android.permission.RECEIVE_MMS", "android.permission.RECEIVE_WAP_PUSH",
            "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS",
            "android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS",
            "android.permission.GET_ACCOUNTS", "android.permission.READ_PHONE_STATE"
        )
        val strictExtra = listOf(
            "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.BODY_SENSORS",
            "android.permission.ACTIVITY_RECOGNITION"
        )
        for (p in base + (if (strict) strictExtra else emptyList())) {
            try {
                dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED)
            } catch (_: Exception) {
            }
        }
    }

    private fun applyStrictToInstalled(strict: Boolean) {
        exec.execute {
            val extra = listOf(
                "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.BODY_SENSORS",
                "android.permission.ACTIVITY_RECOGNITION"
            )
            val apps: List<PackageInfo> = try {
                pm.getInstalledPackages(0)
            } catch (_: Exception) {
                emptyList()
            }
            for (pi in apps) {
                val ai = pi.applicationInfo ?: continue
                if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                if (pi.packageName == packageName) continue
                for (p in extra) {
                    try {
                        dpm.setPermissionGrantState(
                            admin, pi.packageName, p,
                            if (strict) DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                            else DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun refreshList() {
        exec.execute {
            val items = mutableListOf<AppsAdapter.Item>()
            try {
                val pkgs = pm.getInstalledPackages(0)
                for (pi in pkgs) {
                    val ai = pi.applicationInfo ?: continue
                    if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                    if (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) continue
                    if (pi.packageName == packageName) continue
                    val hidden = try {
                        dpm.isApplicationHidden(admin, pi.packageName)
                    } catch (_: Exception) {
                        false
                    }
                    items.add(
                        AppsAdapter.Item(
                            pi.packageName,
                            ai.loadLabel(pm).toString(),
                            pi.versionName ?: "?",
                            ai.loadIcon(pm),
                            hidden
                        )
                    )
                }
            } catch (_: Exception) {
            }
            items.sortBy { it.label.lowercase() }
            runOnUiThread {
                adapter = AppsAdapter(
                    items,
                    onOpen = { item ->
                        val li = pm.getLaunchIntentForPackage(item.pkg)
                        if (li != null) {
                            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(li)
                        } else {
                            Toast.makeText(this, "Is app ki koi launcher screen nahi hai", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFreeze = { item ->
                        exec.execute {
                            try {
                                val nowHidden = dpm.isApplicationHidden(admin, item.pkg)
                                dpm.setApplicationHidden(admin, item.pkg, !nowHidden)
                                runOnUiThread {
                                    Toast.makeText(
                                        this,
                                        if (nowHidden) "${item.label} unfreeze ho gaya ✅"
                                        else "${item.label} freeze ho gaya ❄️",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshList()
                                }
                            } catch (e: Exception) {
                                runOnUiThread {
                                    Toast.makeText(this, "Fail: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    onUninstall = { item ->
                        AlertDialog.Builder(this)
                            .setTitle(item.label)
                            .setMessage("Mod ko space se uninstall karein?")
                            .setPositiveButton("Uninstall") { _, _ ->
                                ApkInstaller.uninstall(this, item.pkg) { ok, msg ->
                                    runOnUiThread {
                                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                                        if (ok) refreshList()
                                    }
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                )
                b.recyclerApps.adapter = adapter
                b.txtEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun msgDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
