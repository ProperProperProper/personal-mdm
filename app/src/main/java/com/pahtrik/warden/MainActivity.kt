package com.pahtrik.warden

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.pahtrik.warden.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots/recording/cast of policy UI — toggleable, default on
        if (Warden.getToggle(this, Warden.T_APP_SCREENSHOT_GUARD)) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.content.visibility = android.view.View.GONE
        authenticate()
    }

    override fun onPause() {
        super.onPause()
        // Lock again whenever the app leaves the foreground
        unlocked = false
        b.content.visibility = android.view.View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (!unlocked) authenticate()
    }

    private fun authenticate() {
        // Fingerprint ONLY: BIOMETRIC_STRONG excludes the A17's class-2 face
        // unlock; no DEVICE_CREDENTIAL means no PIN/password fallback.
        val allowed = BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(allowed) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            Toast.makeText(this, "Enrol a fingerprint to use Personal MDM", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val prompt = BiometricPrompt(
            this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                    b.content.visibility = android.view.View.VISIBLE
                    render()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) = finish()
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Personal MDM")
                .setSubtitle("Fingerprint required")
                .setAllowedAuthenticators(allowed)
                .setNegativeButtonText("Cancel") // required when no device credential
                .build()
        )
    }

    private fun render() {
        b.ownerStatus.text = if (Warden.isDeviceOwner(this))
            "Device owner: ACTIVE — policies enforceable"
        else
            "Device owner: NOT SET — run:\nadb shell dpm set-device-owner com.pahtrik.warden/.WardenAdminReceiver"

        bindToggle(b.swEnforce, Warden.T_ENFORCE_WHITELIST)
        bindToggle(b.swGate, Warden.T_GATE_NEW_APPS)
        bindToggle(b.swBlockInstalls, Warden.T_BLOCK_INSTALLS)
        bindToggle(b.swBlockUninstalls, Warden.T_BLOCK_UNINSTALLS)
        bindToggle(b.swBlockUnknown, Warden.T_BLOCK_UNKNOWN_SOURCES)
        bindToggle(b.swCookies, Warden.T_COOKIE_ALLOWLIST)
        bindToggle(b.swDns, Warden.T_SECURE_DNS)
        bindToggle(b.swKiosk, Warden.T_KIOSK_HOME)
        bindToggle(b.swDownloads, Warden.T_BLOCK_DOWNLOADS)
        bindToggle(b.swHttpsOnly, Warden.T_HTTPS_ONLY)
        bindToggle(b.swDisableNfc, Warden.T_DISABLE_NFC)
        bindToggle(b.swAppScreenshotGuard, Warden.T_APP_SCREENSHOT_GUARD)
        bindToggle(b.swSettingsLockdown, Warden.T_SETTINGS_LOCKDOWN)
        bindToggle(b.swLinkGuard, Warden.T_LINK_GUARD)
        bindToggle(b.swAutoclear, Warden.T_CHROME_AUTOCLEAR)
        bindToggle(b.swSafeboot, Warden.T_DISABLE_SAFEBOOT)
        bindToggle(b.swPassword, Warden.T_PASSWORD_HARDENING)
        bindToggle(b.swNoCapture, Warden.T_NO_SCREEN_CAPTURE)
        bindToggle(b.swMic, Warden.T_MIC_CALLS_ONLY)
        bindToggle(b.swTimeLimits, Warden.T_TIME_LIMITS)
        bindToggle(b.swBlockUserCa, Warden.T_BLOCK_USER_CA)

        b.scanCerts.setOnClickListener {
            val flags = CertScanner.scanAndLog(this)
            b.certScanResult.text = if (flags.isEmpty())
                "No cert-sensitive apps flagged."
            else flags.joinToString("\n") { "⚠ ${it.label}: ${it.reasons.joinToString(", ")}" }
            b.auditView.text = AuditLog.read(this).take(100).joinToString("\n")
        }

        b.sessionLimit.setText(Warden.sessionLimitMin(this).toString())
        b.saveTimeLimits.setOnClickListener {
            val m = b.sessionLimit.text.toString().toIntOrNull() ?: 120
            Warden.setSessionLimit(this, m)
            Toast.makeText(this, "Session limit: $m min", Toast.LENGTH_SHORT).show()
        }

        b.auditView.text = AuditLog.read(this).take(100).joinToString("\n")
        b.clearAudit.setOnClickListener {
            AuditLog.clear(this); b.auditView.text = ""
        }

        b.dnsHost.setText(Warden.dnsHost(this))
        b.dnsHost.setOnFocusChangeListener { _, focused ->
            if (!focused) {
                Warden.setDnsHost(this, b.dnsHost.text.toString())
                Warden.applyDnsPolicy(this, Warden.getToggle(this, Warden.T_SECURE_DNS))
                    ?.let { Toast.makeText(this, "DNS: $it", Toast.LENGTH_LONG).show() }
            }
        }

        b.cookieSites.setText(Warden.cookieSites(this).joinToString("\n"))
        b.saveCookies.setOnClickListener {
            Warden.setCookieSites(this, b.cookieSites.text.toString())
            Toast.makeText(this, "Cookie allowlist saved", Toast.LENGTH_SHORT).show()
        }

        b.appList.layoutManager = LinearLayoutManager(this)
        b.appList.adapter = AppAdapter(Warden.candidateApps(this)) { pkg, on ->
            Warden.setWhitelisted(this, pkg, on)
        }

        bindToggle(b.swFirewall, Warden.T_NET_FIREWALL)
        val allowed = Warden.netAllowed(this)
        b.netList.layoutManager = LinearLayoutManager(this)
        b.netList.adapter = NetAdapter(
            Warden.candidateApps(this).map { app ->
                NetRow(app.pkg, app.label,
                    necessary = app.pkg in Warden.NET_NECESSARY,
                    allowed = app.pkg in allowed)
            }
        ) { pkg, on -> Warden.setNetAllowed(this, pkg, on) }

        val pending = Warden.pending(this)
        b.pendingHeader.text =
            if (pending.isEmpty()) "No pending approvals"
            else "Pending approval: ${pending.joinToString()}"
    }

    private fun bindToggle(sw: MaterialSwitch, key: String) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = Warden.getToggle(this, key)
        sw.setOnCheckedChangeListener { _, on ->
            if (!Warden.isDeviceOwner(this)) {
                Toast.makeText(this, "Not device owner - toggle saved but not enforced", Toast.LENGTH_SHORT).show()
            }
            Warden.setToggle(this, key, on)
            if (key == Warden.T_ENFORCE_WHITELIST) render()
        }
    }
}

data class NetRow(val pkg: String, val label: String, val necessary: Boolean, val allowed: Boolean)

class NetAdapter(
    private val rows: List<NetRow>,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<NetAdapter.VH>() {

    class VH(val sw: MaterialSwitch) : RecyclerView.ViewHolder(sw)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val sw = MaterialSwitch(parent.context)
        sw.setPadding(24, 20, 24, 20)
        return VH(sw)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = rows[pos]
        h.sw.setOnCheckedChangeListener(null)
        h.sw.text = if (r.necessary) "${r.label} — NECESSARY (locked)" else r.label
        h.sw.isChecked = r.allowed || r.necessary
        h.sw.isEnabled = !r.necessary
        h.sw.setOnCheckedChangeListener { _, on -> onToggle(r.pkg, on) }
    }
}

class AppAdapter(
    private val rows: List<Warden.AppRow>,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(val sw: MaterialSwitch) : RecyclerView.ViewHolder(sw)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val sw = MaterialSwitch(parent.context)
        sw.setPadding(24, 20, 24, 20)
        return VH(sw)
    }

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = rows[pos]
        h.sw.setOnCheckedChangeListener(null)
        h.sw.text = "${r.label}\n${r.pkg}${if (r.suspended) "  [suspended]" else ""}"
        h.sw.isChecked = r.whitelisted
        h.sw.setOnCheckedChangeListener { _, on -> onToggle(r.pkg, on) }
    }
}
