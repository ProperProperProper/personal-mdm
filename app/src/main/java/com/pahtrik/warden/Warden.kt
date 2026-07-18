package com.pahtrik.warden

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Core policy engine. All state lives in EncryptedSharedPreferences; all
 * enforcement goes through DevicePolicyManager as device owner.
 */
object Warden {

    const val CHANNEL_APPROVALS = "approvals"

    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_PENDING = "pending"

    // Feature toggles
    const val T_ENFORCE_WHITELIST = "toggle_enforce_whitelist"
    const val T_GATE_NEW_APPS = "toggle_gate_new_apps"
    const val T_BLOCK_INSTALLS = "toggle_block_installs"
    const val T_BLOCK_UNINSTALLS = "toggle_block_uninstalls"
    const val T_BLOCK_UNKNOWN_SOURCES = "toggle_block_unknown_sources"
    const val T_COOKIE_ALLOWLIST = "toggle_cookie_allowlist"
    const val T_SECURE_DNS = "toggle_secure_dns"
    const val T_KIOSK_HOME = "toggle_kiosk_home"
    const val T_NET_FIREWALL = "toggle_net_firewall"
    const val T_BLOCK_DOWNLOADS = "toggle_block_downloads"
    const val T_LINK_GUARD = "toggle_link_guard"
    const val T_MIC_CALLS_ONLY = "toggle_mic_calls_only"
    const val T_TIME_LIMITS = "toggle_time_limits"
    const val T_CHROME_AUTOCLEAR = "toggle_chrome_autoclear"
    const val T_DISABLE_SAFEBOOT = "toggle_disable_safeboot"
    const val T_PASSWORD_HARDENING = "toggle_password_hardening"
    const val T_NO_SCREEN_CAPTURE = "toggle_no_screen_capture"
    const val T_BLOCK_USER_CA = "toggle_block_user_ca"
    const val T_HTTPS_ONLY = "toggle_https_only"
    const val T_DISABLE_NFC = "toggle_disable_nfc"
    const val T_APP_SCREENSHOT_GUARD = "toggle_app_screenshot_guard"
    const val T_SETTINGS_LOCKDOWN = "toggle_settings_lockdown"
    /** Accident-prone Settings surfaces locked by the master lockdown toggle. */
    private val LOCKDOWN_RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_REMOVE_USER,
        UserManager.DISALLOW_CONFIG_TETHERING,
        UserManager.DISALLOW_APPS_CONTROL,      // no force-stop/clear-data/disable of apps
        UserManager.DISALLOW_UNINSTALL_APPS
    )
    /** Non-https schemes routed to the dead-end sink when HTTPS-only is on. */
    val BLOCKED_SCHEMES = listOf(
        "http", "ftp", "ftps", "sftp", "file", "ws", "wss", "content", "data",
        "javascript", "rtsp", "mms"
    )
    private const val KEY_SESSION_LIMIT = "session_limit_min"

    /** Never time-limited: alarms, calls, texting must always be reachable. */
    val TIME_EXEMPT = setOf(
        "com.sec.android.app.clockpackage",   // Clock / alarm
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.phone",
        "com.samsung.android.messaging",
        "com.pahtrik.warden"
    )
    private const val KEY_TIME_LIMITS = "time_limits"

    /** Apps allowed to use the microphone when mic-calls-only is on. */
    val MIC_NECESSARY = setOf(
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.phone",
        "com.sec.imsservice"
    )

    /** Apps that must keep shared-storage/media access or they break. */
    val STORAGE_NECESSARY = setOf(
        "com.sec.android.app.camera",
        "com.sec.android.gallery3d",
        "com.whatsapp"
        // Samsung Messages deliberately excluded: it must not save or read files
    )
    private val STORAGE_PERMS = arrayOf(
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO"
    )
    private const val KEY_NET_ALLOWED = "net_allowed"

    /** Apps whose internet access is required for core function — always
     *  allowed, shown as "Necessary", never editable. */
    val NET_NECESSARY = setOf(
        "com.google.android.gms",            // push, SafetyNet, 2FA plumbing
        "com.google.android.gsf",
        "com.android.phone",                 // IMS/VoLTE/VoWiFi
        "com.sec.imsservice",
        "com.samsung.android.messaging",     // SMS/MMS/RCS
        "com.azure.authenticator",           // useless offline
        "com.lastpass.lpandroid",
        "com.lastpass.authenticator",
        "com.osp.app.signin"                 // Samsung account
    )
    private const val KEY_DNS_HOST = "dns_host"
    private const val KEY_COOKIE_SITES = "cookie_sites"
    private const val CHROME = "com.android.chrome"

    fun admin(ctx: Context): ComponentName =
        ComponentName(ctx, WardenAdminReceiver::class.java)

    fun dpm(ctx: Context): DevicePolicyManager =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isDeviceOwner(ctx: Context): Boolean =
        dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx,
        "warden_secure",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ---- whitelist ----

    fun whitelist(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(KEY_WHITELIST, defaultWhitelist())!!.toMutableSet()

    fun setWhitelisted(ctx: Context, pkg: String, allowed: Boolean) {
        val wl = whitelist(ctx)
        if (allowed) wl.add(pkg) else wl.remove(pkg)
        prefs(ctx).edit().putStringSet(KEY_WHITELIST, wl).apply()
        if (getToggle(ctx, T_ENFORCE_WHITELIST)) enforceWhitelist(ctx)
    }

    private fun defaultWhitelist() = mutableSetOf(
        "com.samsung.android.dialer",
        "com.samsung.android.app.contacts",
        "com.samsung.android.messaging",
        "com.sec.android.app.camera",
        "com.sec.android.gallery3d",
        "com.google.android.apps.maps",
        "com.azure.authenticator",
        "com.lastpass.lpandroid",
        "com.lastpass.authenticator",
        "com.anthropic.claude",
        "com.bybit.app",
        "com.paypal.android.p2pmobile",
        "com.tradingview.tradingviewapp",
        "com.ubnt.easyunifi",
        "com.whatsapp",                        // WhatsApp
        "au.com.cua.mb",                       // Great Southern Bank (ex-CUA)
        "au.gov.mygov.mygovapp",               // myGov
        "com.vicroads.mobileapp",              // VicRoads / myVicRoads
        "au.com.transurban.linkt",             // Linkt tolls
        "com.suncorp.suncorpbank",             // Suncorp Bank
        "com.suncorp.secured",                 // Suncorp Secured
        "com.linkedin.android",                // LinkedIn
        "com.amazon.mShop.android.shopping",   // Amazon
        "com.audible.application",             // Audible
        "com.coinspot.au",                     // CoinSpot
        "com.pahtrik.warden"
    )

    /** Launchable, non-system-critical candidates shown in the UI. */
    fun candidateApps(ctx: Context): List<AppRow> {
        val pm = ctx.packageManager
        val launchable = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
        val wl = whitelist(ctx)
        return launchable.map {
            AppRow(
                pkg = it.packageName,
                label = pm.getApplicationLabel(it).toString(),
                system = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                whitelisted = it.packageName in wl,
                suspended = isSuspended(ctx, it.packageName)
            )
        }.sortedBy { it.label.lowercase() }
    }

    private fun isSuspended(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.isPackageSuspended(pkg)
    } catch (_: Exception) { false }

    /** Suspend every launchable app not on the whitelist; unsuspend the rest. */
    fun enforceWhitelist(ctx: Context): Pair<Int, Int> {
        if (!isDeviceOwner(ctx)) return 0 to 0
        val wl = whitelist(ctx)
        val apps = candidateApps(ctx)
        val toSuspend = apps.filter { !it.whitelisted && it.pkg != ctx.packageName }
            .map { it.pkg }.toTypedArray()
        val toRestore = apps.filter { it.whitelisted }.map { it.pkg }.toTypedArray()
        val d = dpm(ctx); val a = admin(ctx)
        var suspended = 0
        if (toSuspend.isNotEmpty())
            suspended = toSuspend.size - d.setPackagesSuspended(a, toSuspend, true).size
        if (toRestore.isNotEmpty())
            d.setPackagesSuspended(a, toRestore, false)
        wl.forEach { runCatching { d.setUninstallBlocked(a, it, getToggle(ctx, T_BLOCK_UNINSTALLS)) } }
        return suspended to toRestore.size
    }

    fun liftAll(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val all = candidateApps(ctx).map { it.pkg }.toTypedArray()
        dpm(ctx).setPackagesSuspended(admin(ctx), all, false)
    }

    // ---- toggles ----

    fun getToggle(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean(key, key == T_GATE_NEW_APPS || key == T_APP_SCREENSHOT_GUARD)

    fun setToggle(ctx: Context, key: String, on: Boolean) {
        prefs(ctx).edit().putBoolean(key, on).apply()
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        when (key) {
            T_ENFORCE_WHITELIST -> if (on) enforceWhitelist(ctx) else liftAll(ctx)
            T_BLOCK_INSTALLS ->
                if (on) d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)
                else d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_APPS)
            T_BLOCK_UNINSTALLS -> enforceWhitelist(ctx)
            T_BLOCK_UNKNOWN_SOURCES ->
                if (on) d.addUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                else d.clearUserRestriction(a, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            T_COOKIE_ALLOWLIST -> applyCookiePolicy(ctx, on)
            T_SECURE_DNS -> applyDnsPolicy(ctx, on)
            T_KIOSK_HOME -> applyKioskHome(ctx, on)
            T_NET_FIREWALL -> applyFirewall(ctx, on)
            T_BLOCK_DOWNLOADS -> applyDownloadBlock(ctx, on)
            T_LINK_GUARD -> applyLinkGuard(ctx, on)
            T_MIC_CALLS_ONLY -> applyMicPolicy(ctx, on)
            T_TIME_LIMITS -> {
                if (on) {
                    grantUsageAccess(ctx); scheduleUsageGuard(ctx)
                    runCatching { d.setAutoTimeEnabled(a, true) } // clock from network
                    d.addUserRestriction(a, UserManager.DISALLOW_CONFIG_DATE_TIME)
                } else d.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_DATE_TIME)
            }
            T_CHROME_AUTOCLEAR -> applyChromePolicy(ctx)
            T_DISABLE_SAFEBOOT ->
                if (on) d.addUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT)
                else d.clearUserRestriction(a, UserManager.DISALLOW_SAFE_BOOT)
            T_PASSWORD_HARDENING -> applyPasswordPolicy(ctx, on)
            T_NO_SCREEN_CAPTURE -> d.setScreenCaptureDisabled(a, on)
            T_BLOCK_USER_CA ->
                if (on) {
                    d.addUserRestriction(a, UserManager.DISALLOW_CONFIG_CREDENTIALS)
                    CertScanner.scanAndLog(ctx) // flag potential false positives
                } else d.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_CREDENTIALS)
            T_HTTPS_ONLY -> applyHttpsOnly(ctx, on)
            T_SETTINGS_LOCKDOWN -> LOCKDOWN_RESTRICTIONS.forEach {
                if (on) d.addUserRestriction(a, it) else d.clearUserRestriction(a, it)
            }
            T_DISABLE_NFC ->
                // literal key; constant added API 33 but not on all compile paths
                if (on) d.addUserRestriction(a, "no_near_field_communication_radio")
                else d.clearUserRestriction(a, "no_near_field_communication_radio")
        }
        AuditLog.add(ctx, "POLICY ${key.removePrefix("toggle_")} = $on")
    }

    // ---- Chrome cookie allowlist (managed configuration) ----

    fun cookieSites(ctx: Context): MutableList<String> =
        prefs(ctx).getString(KEY_COOKIE_SITES, defaultCookieSites())!!
            .lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()

    fun setCookieSites(ctx: Context, sites: String) {
        prefs(ctx).edit().putString(KEY_COOKIE_SITES, sites).apply()
        if (getToggle(ctx, T_COOKIE_ALLOWLIST)) applyCookiePolicy(ctx, true)
    }

    private fun defaultCookieSites() = listOf(
        "[*.]lastpass.com",
        "[*.]microsoft.com",
        "[*.]microsoftonline.com",
        "[*.]samsung.com",
        "[*.]google.com"
    ).joinToString("\n")

    /** Chrome managed policy bundle is built from BOTH the cookie-allowlist
     *  and download-block toggles, since setApplicationRestrictions replaces
     *  the whole bundle. */
    fun applyChromePolicy(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val bundle = android.os.Bundle()
        if (getToggle(ctx, T_COOKIE_ALLOWLIST)) {
            bundle.putInt("DefaultCookiesSetting", 2) // 2 = block all cookies
            bundle.putStringArray("CookiesAllowedForUrls", cookieSites(ctx).toTypedArray())
        }
        if (getToggle(ctx, T_BLOCK_DOWNLOADS)) {
            bundle.putInt("DownloadRestrictions", 3) // 3 = block ALL downloads
            bundle.putBoolean("PromptForDownloadLocation", false)
        }
        if (getToggle(ctx, T_HTTPS_ONLY)) {
            bundle.putString("HttpsOnlyMode", "force_enabled")     // block plain http
            bundle.putBoolean("HttpsUpgradesEnabled", true)
            bundle.putStringArray("URLBlocklist",
                arrayOf("ftp://*", "ftps://*", "sftp://*", "file://*", "ws://*", "wss://*",
                        "http://*", "javascript://*", "rtsp://*", "mms://*", "data://*"))
            bundle.putStringArray("URLAllowlist", arrayOf("https://*"))
        }
        if (getToggle(ctx, T_CHROME_AUTOCLEAR)) {
            bundle.putStringArray("ClearBrowsingDataOnExitList",
                arrayOf("browsing_history", "download_history", "cookies_and_other_site_data",
                        "cached_images_and_files", "password_signin", "autofill", "site_settings"))
        }
        runCatching { dpm(ctx).setApplicationRestrictions(admin(ctx), CHROME, bundle) }
    }

    fun applyCookiePolicy(ctx: Context, @Suppress("UNUSED_PARAMETER") on: Boolean) =
        applyChromePolicy(ctx)

    /** Deny shared-storage/media permissions to everything except the
     *  media-necessary set; block USB file transfer and external media. */
    fun applyDownloadBlock(ctx: Context, on: Boolean) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        applyChromePolicy(ctx)
        val state = if (on) DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    else DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        candidateApps(ctx)
            .filter { it.pkg !in STORAGE_NECESSARY && it.pkg != ctx.packageName }
            .forEach { app ->
                STORAGE_PERMS.forEach { perm ->
                    runCatching { d.setPermissionGrantState(a, app.pkg, perm, state) }
                }
            }
        if (on) {
            d.addUserRestriction(a, UserManager.DISALLOW_USB_FILE_TRANSFER)
            d.addUserRestriction(a, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
        } else {
            d.clearUserRestriction(a, UserManager.DISALLOW_USB_FILE_TRANSFER)
            d.clearUserRestriction(a, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
        }
    }

    // ---- secure DNS (Private DNS / DoT, enforced device-wide) ----

    fun dnsHost(ctx: Context): String =
        prefs(ctx).getString(KEY_DNS_HOST, "dns.quad9.net")!!

    fun setDnsHost(ctx: Context, host: String) {
        prefs(ctx).edit().putString(KEY_DNS_HOST, host.trim()).apply()
        if (getToggle(ctx, T_SECURE_DNS)) applyDnsPolicy(ctx, true)
    }

    /** Returns null on success, else an error message. */
    fun applyDnsPolicy(ctx: Context, on: Boolean): String? {
        if (!isDeviceOwner(ctx)) return "not device owner"
        if (android.os.Build.VERSION.SDK_INT < 29) return "requires Android 10+"
        val d = dpm(ctx); val a = admin(ctx)
        return try {
            if (on) {
                val code = d.setGlobalPrivateDnsModeSpecifiedHost(a, dnsHost(ctx))
                if (code == DevicePolicyManager.PRIVATE_DNS_SET_NO_ERROR) null
                else "host validation failed ($code)"
            } else {
                d.setGlobalPrivateDnsModeOpportunistic(a); null
            }.also {
                // Lock the Private DNS control in Settings while enforced
                if (on) d.addUserRestriction(a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
                else d.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
            }
        } catch (e: Exception) { e.message }
    }

    // ---- per-app internet firewall ----

    /** Packages permitted to use the internet (necessary set always included). */
    fun netAllowed(ctx: Context): Set<String> {
        val user = prefs(ctx).getStringSet(KEY_NET_ALLOWED, defaultNetAllowed())!!
        return user + NET_NECESSARY
    }

    private fun defaultNetAllowed() = mutableSetOf(
        "com.android.chrome",
        "com.google.android.apps.maps",
        "com.anthropic.claude",
        "com.bybit.app",
        "com.paypal.android.p2pmobile",
        "com.tradingview.tradingviewapp",
        "com.ubnt.easyunifi",
        "com.whatsapp",
        "au.com.cua.mb",
        "au.gov.mygov.mygovapp",
        "com.vicroads.mobileapp",
        "au.com.transurban.linkt",
        "com.suncorp.suncorpbank",
        "com.suncorp.secured",
        "com.linkedin.android",
        "com.amazon.mShop.android.shopping",
        "com.audible.application",
        "com.coinspot.au"
    )

    fun setNetAllowed(ctx: Context, pkg: String, allowed: Boolean) {
        if (pkg in NET_NECESSARY) return // necessary rows are immutable
        val s = prefs(ctx).getStringSet(KEY_NET_ALLOWED, defaultNetAllowed())!!.toMutableSet()
        if (allowed) s.add(pkg) else s.remove(pkg)
        prefs(ctx).edit().putStringSet(KEY_NET_ALLOWED, s).apply()
        if (getToggle(ctx, T_NET_FIREWALL)) applyFirewall(ctx, true)
    }

    /** Engage/disengage the sinkhole. Device owner activates its own VPN
     *  always-on + lockdown, so no consent dialog and no leak on reboot. */
    fun applyFirewall(ctx: Context, on: Boolean) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        if (on) {
            runCatching { d.setAlwaysOnVpnPackage(a, ctx.packageName, true) }
            ctx.startService(android.content.Intent(ctx, WardenVpnService::class.java))
            d.addUserRestriction(a, UserManager.DISALLOW_CONFIG_VPN) // no Settings override
        } else {
            runCatching { d.setAlwaysOnVpnPackage(a, null, false) }
            ctx.stopService(android.content.Intent(ctx, WardenVpnService::class.java))
            d.clearUserRestriction(a, UserManager.DISALLOW_CONFIG_VPN)
        }
    }

    // ---- password hardening ----

    fun applyPasswordPolicy(ctx: Context, on: Boolean) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            d.setRequiredPasswordComplexity(
                if (on) DevicePolicyManager.PASSWORD_COMPLEXITY_HIGH
                else DevicePolicyManager.PASSWORD_COMPLEXITY_NONE
            )
        } else {
            @Suppress("DEPRECATION")
            if (on) {
                d.setPasswordQuality(a, DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC)
                d.setPasswordMinimumLength(a, 6)
            } else {
                d.setPasswordQuality(a, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
            }
        }
        // Lock after 10 failed attempts (device owner: wipes; keep conservative)
        d.setMaximumFailedPasswordsForWipe(a, if (on) 10 else 0)
        // Auto-lock timeout 60s
        d.setMaximumTimeToLock(a, if (on) 60_000L else 0L)
        AuditLog.add(ctx, "PASSWORD hardening = $on")
    }

    // ---- microphone: calls only ----

    fun applyMicPolicy(ctx: Context, on: Boolean) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        val state = if (on) DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                    else DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        candidateApps(ctx)
            .filter { it.pkg !in MIC_NECESSARY && it.pkg != ctx.packageName }
            .forEach {
                runCatching {
                    d.setPermissionGrantState(a, it.pkg,
                        "android.permission.RECORD_AUDIO", state)
                }
            }
        AuditLog.add(ctx, "MIC calls-only = $on")
    }

    // ---- app time limits ----

    /** Global max continuous-open minutes for any non-exempt app. */
    fun sessionLimitMin(ctx: Context): Int =
        prefs(ctx).getInt(KEY_SESSION_LIMIT, 120)

    fun setSessionLimit(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_SESSION_LIMIT, minutes.coerceIn(1, 1440)).apply()
        AuditLog.add(ctx, "SESSION LIMIT = $minutes min")
        if (getToggle(ctx, T_TIME_LIMITS)) scheduleUsageGuard(ctx)
    }

    /** Cooldown before a session-suspended app may be reopened (minutes). */
    private const val SESSION_COOLDOWN_MS = 30 * 60_000L
    private const val KEY_SESSION_SUSPENDS = "session_suspends"

    fun recordSessionSuspend(ctx: Context, pkg: String, at: Long) {
        val m = prefs(ctx).getStringSet(KEY_SESSION_SUSPENDS, emptySet())!!.toMutableSet()
        m.removeAll { it.startsWith("$pkg=") }
        m.add("$pkg=$at")
        prefs(ctx).edit().putStringSet(KEY_SESSION_SUSPENDS, m).apply()
    }

    /** Unsuspend apps whose cooldown has passed (or all, if force). */
    fun sweepSessionCooldowns(ctx: Context, now: Long, force: Boolean = false) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        val m = prefs(ctx).getStringSet(KEY_SESSION_SUSPENDS, emptySet())!!.toMutableSet()
        val wl = whitelist(ctx)
        val iter = m.iterator()
        val toLift = mutableListOf<String>()
        while (iter.hasNext()) {
            val (pkg, ts) = iter.next().split("=").let { it[0] to (it.getOrNull(1)?.toLongOrNull() ?: 0L) }
            if (force || now - ts >= SESSION_COOLDOWN_MS) {
                if (pkg in wl) toLift.add(pkg)
                iter.remove()
            }
        }
        if (toLift.isNotEmpty()) {
            d.setPackagesSuspended(a, toLift.toTypedArray(), false)
            prefs(ctx).edit().putStringSet(KEY_SESSION_SUSPENDS, m).apply()
            AuditLog.add(ctx, "SESSION cooldown lifted: ${toLift.joinToString()}")
        }
    }

    /** As device owner, grant ourselves usage-access so limits can be metered. */
    fun grantUsageAccess(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        runCatching {
            dpm(ctx).setPermissionGrantState(admin(ctx), ctx.packageName,
                "android.permission.PACKAGE_USAGE_STATS",
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        }
    }

    fun scheduleUsageGuard(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getService(
            ctx, 100, android.content.Intent(ctx, UsageGuardService::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        // Check every 60s while limits are active
        am.setInexactRepeating(
            android.app.AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 60_000L,
            60_000L, pi
        )
    }

    // ---- kiosk home launcher + link guard ----
    // Both use persistent preferred activities. Clearing is package-wide, so
    // any change re-applies whichever of the two is still toggled on.

    fun applyKioskHome(ctx: Context, on: Boolean) = reapplyPreferred(ctx, kiosk = on,
        links = getToggle(ctx, T_LINK_GUARD), httpsOnly = getToggle(ctx, T_HTTPS_ONLY))

    fun applyLinkGuard(ctx: Context, on: Boolean) = reapplyPreferred(ctx,
        kiosk = getToggle(ctx, T_KIOSK_HOME), links = on, httpsOnly = getToggle(ctx, T_HTTPS_ONLY))

    fun applyHttpsOnly(ctx: Context, on: Boolean) {
        applyChromePolicy(ctx) // Chrome-side blocklist
        reapplyPreferred(ctx, kiosk = getToggle(ctx, T_KIOSK_HOME),
            links = getToggle(ctx, T_LINK_GUARD), httpsOnly = on)
    }

    private fun reapplyPreferred(ctx: Context, kiosk: Boolean, links: Boolean, httpsOnly: Boolean) {
        if (!isDeviceOwner(ctx)) return
        val d = dpm(ctx); val a = admin(ctx)
        d.clearPackagePersistentPreferredActivities(a, ctx.packageName)
        val sink = ComponentName(ctx, LinkGuardActivity::class.java)
        if (kiosk) {
            val home = android.content.IntentFilter(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            }
            d.addPersistentPreferredActivity(a, home, ComponentName(ctx, HomeActivity::class.java))
        }
        // Link Guard: dead-end BOTH http and https (all web links inert).
        // HTTPS-only (without Link Guard): dead-end only the non-https schemes,
        // leaving https to open normally in Chrome.
        val schemesToSink = when {
            links -> listOf("http", "https") + BLOCKED_SCHEMES
            httpsOnly -> BLOCKED_SCHEMES
            else -> emptyList()
        }.distinct()
        for (scheme in schemesToSink) {
            val f = android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                addDataScheme(scheme)
            }
            d.addPersistentPreferredActivity(a, f, sink)
        }
    }

    // ---- pending approvals ----

    fun pending(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(KEY_PENDING, emptySet())!!.toMutableSet()

    fun addPending(ctx: Context, pkg: String) {
        val p = pending(ctx); p.add(pkg)
        prefs(ctx).edit().putStringSet(KEY_PENDING, p).apply()
    }

    fun resolvePending(ctx: Context, pkg: String, approve: Boolean) {
        val p = pending(ctx); p.remove(pkg)
        prefs(ctx).edit().putStringSet(KEY_PENDING, p).apply()
        if (approve) {
            setWhitelisted(ctx, pkg, true)
            if (isDeviceOwner(ctx))
                dpm(ctx).setPackagesSuspended(admin(ctx), arrayOf(pkg), false)
        } else if (isDeviceOwner(ctx)) {
            dpm(ctx).setPackagesSuspended(admin(ctx), arrayOf(pkg), true)
        }
    }

    data class AppRow(
        val pkg: String,
        val label: String,
        val system: Boolean,
        val whitelisted: Boolean,
        val suspended: Boolean
    )
}
