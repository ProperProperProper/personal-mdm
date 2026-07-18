package com.pahtrik.warden

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.pahtrik.warden.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var unlocked = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        if (Warden.getToggle(this, Warden.T_APP_SCREENSHOT_GUARD)) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        b.content.visibility = View.GONE
        authenticate()
    }

    override fun onPause() {
        super.onPause()
        unlocked = false
        b.content.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        if (!unlocked) authenticate()
    }

    private fun authenticate() {
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
                    b.content.visibility = View.VISIBLE
                    buildUi()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) = finish()
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Personal MDM")
                .setSubtitle("Fingerprint required")
                .setAllowedAuthenticators(allowed)
                .setNegativeButtonText("Cancel")
                .build()
        )
    }

    // ---------- UI construction ----------

    private fun buildUi() {
        val root = b.container
        root.removeAllViews()

        // Device-owner banner
        root.addView(banner(
            if (Warden.isDeviceOwner(this))
                "● Device owner active — policies enforceable"
            else
                "○ NOT device owner — enrol via adb (see README). Toggles won't enforce."
        ))

        Features.sections.forEach { section ->
            root.addView(sectionHeader(section.title))
            section.features.forEach { f -> root.addView(featureCard(f)) }
        }

        // Per-app whitelist
        root.addView(sectionHeader("App whitelist"))
        root.addView(card {
            it.addView(hint("Apps allowed to run when 'Enforce app whitelist' is on."))
            val rv = RecyclerView(this)
            rv.layoutManager = LinearLayoutManager(this)
            rv.isNestedScrollingEnabled = false
            rv.adapter = AppAdapter(Warden.candidateApps(this)) { pkg, on ->
                Warden.setWhitelisted(this, pkg, on)
            }
            it.addView(rv)
        })

        // Audit log
        root.addView(sectionHeader("Audit log"))
        root.addView(card {
            val clear = MaterialButton(this)
            clear.text = "Clear log"
            val log = mono(AuditLog.read(this).take(120).joinToString("\n"))
            clear.setOnClickListener { _ -> AuditLog.clear(this); log.text = "" }
            it.addView(clear)
            it.addView(log)
        })
    }

    private fun banner(text: String): View {
        val tv = TextView(this)
        tv.text = text
        tv.setPadding(dp(16), dp(14), dp(16), dp(14))
        tv.textSize = 13f
        val card = MaterialCardView(this)
        card.radius = dp(14).toFloat()
        card.setContentPadding(0, 0, 0, 0)
        card.addView(tv)
        card.layoutParams = marginParams()
        return card
    }

    private fun sectionHeader(title: String): View {
        val tv = TextView(this)
        tv.text = title.uppercase()
        tv.setTextColor(ContextCompat.getColor(this, R.color.warden_primary))
        tv.textSize = 12f
        tv.setTypeface(tv.typeface, android.graphics.Typeface.BOLD)
        tv.letterSpacing = 0.08f
        tv.setPadding(dp(8), dp(18), dp(8), dp(6))
        return tv
    }

    /** A feature card: title + ⓘ + switch, plus optional inline editor. */
    private fun featureCard(f: Feature): View = card { col ->
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = f.title
        title.textSize = 16f
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val info = android.widget.ImageButton(this)
        info.setImageResource(R.drawable.ic_info)
        info.background = null
        info.setPadding(dp(8), dp(8), dp(8), dp(8))
        info.contentDescription = "About ${f.title}"
        info.setOnClickListener { showInfo(f) }

        val sw = MaterialSwitch(this)
        bindSwitch(sw, f.key)

        row.addView(title); row.addView(info); row.addView(sw)
        col.addView(row)

        f.editor?.let { col.addView(buildEditor(it)) }
    }

    private fun showInfo(f: Feature) {
        val body = buildString {
            append(f.what)
            append("\n\n▲ Turn ON:  "); append(f.whyOn)
            append("\n▼ Leave OFF:  "); append(f.whyOff)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(f.title)
            .setMessage(body)
            .setPositiveButton("Got it", null)
            .show()
    }

    private fun bindSwitch(sw: MaterialSwitch, key: String) {
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = Warden.getToggle(this, key)
        sw.setOnCheckedChangeListener { _, on ->
            if (!Warden.isDeviceOwner(this))
                Toast.makeText(this, "Not device owner — saved but not enforced", Toast.LENGTH_SHORT).show()
            Warden.setToggle(this, key, on)
        }
    }

    // ---------- inline editors ----------

    private fun buildEditor(kind: String): View = when (kind) {
        "session" -> vbox { box ->
            val status = TextView(this)
            val has = Warden.hasUsageAccess(this)
            status.text = if (has) "Usage access: granted — limits functional"
                          else "Usage access: NOT granted — limits won't work"
            status.textSize = 12f
            status.setTypeface(status.typeface, android.graphics.Typeface.BOLD)
            box.addView(status)
            if (!has) box.addView(MaterialButton(this).apply {
                text = "Grant usage access"
                setOnClickListener { Warden.openUsageAccessSettings(this@MainActivity) }
            })
            val field = numField("Minutes an app may stay open", Warden.sessionLimitMin(this).toString())
            box.addView(field)
            box.addView(saveBtn("Save session limit") {
                Warden.setSessionLimit(this, field.text.toString().toIntOrNull() ?: 120)
            })
            box.addView(hint("Exempt: Clock, Phone, Messages. 30-min cooldown before reopen."))
        }
        "dns" -> vbox { box ->
            val field = textField("DoT hostname, e.g. dns.quad9.net", Warden.dnsHost(this))
            box.addView(field)
            box.addView(saveBtn("Save DNS host") {
                Warden.setDnsHost(this, field.text.toString())
            })
        }
        "cookies" -> vbox { box ->
            val field = multiField("Allowed cookie sites, one per line", Warden.cookieSites(this).joinToString("\n"))
            box.addView(field)
            box.addView(saveBtn("Save cookie allowlist") {
                Warden.setCookieSites(this, field.text.toString())
            })
        }
        "wifi" -> vbox { box ->
            val field = multiField("Allowed Wi-Fi SSIDs, one per line", Warden.wifiSsids(this).joinToString("\n"))
            box.addView(field)
            box.addView(saveBtn("Save allowed Wi-Fi") {
                Warden.setWifiSsids(this, field.text.toString())
            })
        }
        "netlist" -> vbox { box ->
            box.addView(hint("Which apps may use the internet. 'Necessary' apps are locked on."))
            val rv = RecyclerView(this)
            rv.layoutManager = LinearLayoutManager(this)
            rv.isNestedScrollingEnabled = false
            val allowed = Warden.netAllowed(this)
            rv.adapter = NetAdapter(Warden.candidateApps(this).map {
                NetRow(it.pkg, it.label, it.pkg in Warden.NET_NECESSARY, it.pkg in allowed)
            }) { pkg, on -> Warden.setNetAllowed(this, pkg, on) }
            box.addView(rv)
        }
        "certscan" -> vbox { box ->
            val result = TextView(this); result.textSize = 11f
            box.addView(MaterialButton(this).apply {
                text = "Scan for cert-sensitive apps"
                setOnClickListener {
                    val flags = CertScanner.scanAndLog(this@MainActivity)
                    result.text = if (flags.isEmpty()) "No cert-sensitive apps flagged."
                        else flags.joinToString("\n") { "⚠ ${it.label}: ${it.reasons.joinToString(", ")}" }
                }
            })
            box.addView(result)
        }
        else -> View(this)
    }

    // ---------- small view helpers ----------

    private fun card(build: (LinearLayout) -> Unit): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(12), dp(16), dp(12))
        build(col)
        val mc = MaterialCardView(this)
        mc.radius = dp(16).toFloat()
        mc.cardElevation = dp(1).toFloat()
        mc.layoutParams = marginParams()
        mc.addView(col)
        return mc
    }

    private fun vbox(build: (LinearLayout) -> Unit): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(0, dp(8), 0, 0)
        build(col)
        return col
    }

    private fun marginParams(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(dp(4), dp(4), dp(4), dp(4))
        return lp
    }

    private fun hint(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f; alpha = 0.7f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun mono(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(0, dp(8), 0, 0)
    }

    private fun textField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun numField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }

    private fun multiField(hintText: String, value: String) = EditText(this).apply {
        hint = hintText; setText(value); minLines = 2
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun saveBtn(label: String, action: () -> Unit) = MaterialButton(this).apply {
        text = label
        setOnClickListener { action(); Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show() }
    }
}

// ---------- RecyclerView adapters ----------

data class NetRow(val pkg: String, val label: String, val necessary: Boolean, val allowed: Boolean)

class NetAdapter(
    private val rows: List<NetRow>,
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<NetAdapter.VH>() {
    class VH(val sw: MaterialSwitch) : RecyclerView.ViewHolder(sw)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val sw = MaterialSwitch(parent.context)
        sw.setPadding(8, 18, 8, 18)
        return VH(sw)
    }
    override fun getItemCount() = rows.size
    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = rows[pos]
        h.sw.setOnCheckedChangeListener(null)
        h.sw.text = if (r.necessary) "${r.label} — necessary (locked)" else r.label
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
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val sw = MaterialSwitch(parent.context)
        sw.setPadding(8, 18, 8, 18)
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
