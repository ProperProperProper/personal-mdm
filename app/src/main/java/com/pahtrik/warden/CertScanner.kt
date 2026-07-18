package com.pahtrik.warden

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Heuristic scan for apps likely to break when user-added CA certs are
 * blocked (DISALLOW_CONFIG_CREDENTIALS). Android exposes no runtime "TLS
 * failed due to missing user cert" signal, so this flags *potential* false
 * positives from readable signals only — it is advisory, not proof.
 */
object CertScanner {

    /** Package-name fragments for categories that commonly pin to a private
     *  CA or need user-installed certs (VPN, enterprise, MDM, some banking). */
    private val SENSITIVE_HINTS = listOf(
        "vpn", "openvpn", "wireguard", "cisco", "anyconnect", "pulse", "forti",
        "citrix", "globalprotect", "paloalto", "intune", "workspaceone",
        "mobileiron", "knox", "enterprise", "corp", "proxy", "mitm"
    )

    data class Flag(val pkg: String, val label: String, val reasons: List<String>)

    fun scan(ctx: Context): List<Flag> {
        val pm = ctx.packageManager
        val flags = mutableListOf<Flag>()

        // Apps that provide a VPN service — these very often need a user CA.
        val vpnProviders = pm.queryIntentServices(
            Intent("android.net.VpnService"), 0
        ).mapNotNull { it.serviceInfo?.packageName }.toSet()

        val whitelisted = Warden.whitelist(ctx)
        for (pkg in whitelisted) {
            if (pkg == ctx.packageName) continue
            val reasons = mutableListOf<String>()
            val info = try {
                pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            } catch (_: Exception) { continue }

            if (pkg in vpnProviders) reasons.add("provides VPN service")

            val requested = info.requestedPermissions?.toSet() ?: emptySet()
            if ("android.permission.BIND_VPN_SERVICE" in requested)
                reasons.add("VPN permission")

            // Custom network security config often means private-CA trust rules
            val ai = info.applicationInfo
            if (ai != null) {
                try {
                    val field = ai.javaClass.getField("networkSecurityConfigRes")
                    if (field.getInt(ai) != 0) reasons.add("custom network security config")
                } catch (_: Exception) { /* field hidden on some builds */ }
            }

            val low = pkg.lowercase()
            SENSITIVE_HINTS.firstOrNull { low.contains(it) }
                ?.let { reasons.add("name matches '$it'") }

            if (reasons.isNotEmpty()) {
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                            catch (_: Exception) { pkg }
                flags.add(Flag(pkg, label, reasons))
            }
        }
        return flags
    }

    /** Run the scan and write results to the audit log. */
    fun scanAndLog(ctx: Context): List<Flag> {
        val flags = scan(ctx)
        if (flags.isEmpty()) {
            AuditLog.add(ctx, "CERT SCAN: no cert-sensitive apps flagged")
        } else {
            flags.forEach {
                AuditLog.add(ctx,
                    "CERT FALSE-POSITIVE RISK: ${it.label} (${it.pkg}) — ${it.reasons.joinToString("; ")}")
            }
        }
        return flags
    }
}
