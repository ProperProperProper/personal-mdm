package com.pahtrik.warden

/**
 * Declarative catalogue of every toggleable feature, its section, and the
 * help text shown by the info (ⓘ) icon. UI is generated from this list, so
 * adding a feature here + a case in Warden.setToggle is all that's needed.
 */
data class Feature(
    val key: String,
    val title: String,
    val what: String,       // what it does
    val whyOn: String,      // reason to enable
    val whyOff: String,     // reason to leave off
    val editor: String? = null // optional inline editor id handled in MainActivity
)

data class Section(val title: String, val features: List<Feature>)

object Features {

    val sections = listOf(
        Section("Apps", listOf(
            Feature(Warden.T_ENFORCE_WHITELIST, "Enforce app whitelist",
                "Suspends every launchable app that isn't on your allowed list. Suspended apps can't open and show a 'blocked by admin' message.",
                "Turns the phone into an allow-list device — only apps you've approved run at all.",
                "Leave off while you're still deciding which apps you need; nothing is blocked until it's on."),
            Feature(Warden.T_GATE_NEW_APPS, "Gate new apps",
                "Any newly installed app is instantly quarantined (suspended) and you get an Approve / Deny notification.",
                "Nothing new can run without your explicit approval — stops silent or accidental installs from launching.",
                "Off means new installs run immediately with no approval step."),
            Feature(Warden.T_BLOCK_INSTALLS, "Block all app installs",
                "Prevents installing any app from any source, store or sideload.",
                "Hard lock when the app set is final — the phone becomes install-proof.",
                "Leave off while you still need to install apps (e.g. the authenticators)."),
            Feature(Warden.T_BLOCK_UNINSTALLS, "Block uninstalling whitelisted apps",
                "Whitelisted apps can't be removed from the device.",
                "Stops important apps (banking, authenticators) being deleted by accident or by someone else.",
                "Off if you want to freely remove apps yourself."),
            Feature(Warden.T_BLOCK_UNKNOWN_SOURCES, "Block sideloading",
                "Disallows installing apps from unknown sources (APK files, non-store installers).",
                "Closes the most common malware entry point.",
                "Off only if you deliberately sideload trusted APKs."),
            Feature(Warden.T_TIME_LIMITS, "Session time limit",
                "Any non-exempt app auto-closes after it's been open continuously for the set number of minutes, then can't reopen for 30 minutes. Clock, Phone and Messages are never limited.",
                "Curbs doom-scrolling and long unbroken sessions on distracting apps.",
                "Off if you don't want any app force-closed on a timer.",
                editor = "session"),
            Feature(Warden.T_BLOCK_MIRRORING, "Block sharing / casting",
                "Disables screen mirroring (Smart View, Miracast), Quick Share, DLNA and NFC beam. adb to your Mac is unaffected.",
                "Stops the phone sending anything to other devices — no casting, no nearby share.",
                "Off if you want to cast to a TV or share files to nearby devices.")
        )),
        Section("Network", listOf(
            Feature(Warden.T_NET_FIREWALL, "Per-app internet firewall",
                "Routes blocked apps into a local sinkhole VPN so only allowed apps reach the internet. 'Necessary' apps are always allowed.",
                "Tight control of exactly which apps can talk to the internet.",
                "Off if you want every app to have normal network access.",
                editor = "netlist"),
            Feature(Warden.T_SECURE_DNS, "Secure DNS (DNS-over-TLS)",
                "Forces all DNS lookups through an encrypted resolver and locks the Private DNS setting.",
                "Nobody on your network can see or hijack which sites you look up.",
                "Off only if a network needs its own DNS to work (rare).",
                editor = "dns"),
            Feature(Warden.T_HTTPS_ONLY, "HTTPS only",
                "Blocks http, ftp, sftp, file, ws and other insecure schemes; only https opens.",
                "Guarantees encrypted connections; blocks plaintext and legacy protocols.",
                "Off if you must reach an old http-only site."),
            Feature(Warden.T_LINK_GUARD, "Link Guard",
                "Every tapped web link opens an inert screen showing the URL as plain text instead of loading it.",
                "Neutralises phishing links in texts/emails — a tap can't open anything dangerous.",
                "Off if you want links to open normally in the browser."),
            Feature(Warden.T_COOKIE_ALLOWLIST, "Cookie allowlist (Chrome)",
                "Blocks all cookies except sites you list.",
                "Stops tracking cookies while keeping logins you care about.",
                "Off if you want normal cookie behaviour.",
                editor = "cookies"),
            Feature(Warden.T_CHROME_AUTOCLEAR, "Auto-clear browsing data",
                "Wipes Chrome history, cookies, cache, downloads and passwords every time it closes.",
                "Leaves no browsing trace between sessions.",
                "Off if you want Chrome to remember history and logins."),
            Feature(Warden.T_BLOCK_TETHERING, "Block tethering / hotspot",
                "Blocks Bluetooth, USB, Ethernet and Wi-Fi hotspot internet sharing. adb dev to your Mac is unaffected.",
                "Stops the phone being used as an internet bridge for other devices.",
                "Off if you need to share the phone's connection."),
            Feature(Warden.T_WIFI_LOCK, "Lock Wi-Fi to allowed networks",
                "Only connects to SSIDs you list, requires WPA2/WPA3, and blocks adding new networks.",
                "Stops the phone joining rogue or open 'evil-twin' networks.",
                "Off if you connect to many different networks.",
                editor = "wifi")
        )),
        Section("Radios & sharing", listOf(
            Feature(Warden.T_DISABLE_NFC, "Disable NFC",
                "Turns the NFC radio off entirely.",
                "Maximum lockdown if you never use contactless.",
                "Leave OFF to keep tap-to-pay working — NFC can't be limited to payments only."),
            Feature(Warden.T_BLUETOOTH_LOCK, "Lock Bluetooth",
                "Blocks pairing any new device and Bluetooth file sharing. Already-paired devices (your earbuds) keep working.",
                "Only your existing earbuds can ever connect — no new pairings.",
                "Off if you need to pair a new Bluetooth device.")
        )),
        Section("Privacy", listOf(
            Feature(Warden.T_MIC_CALLS_ONLY, "Microphone: calls only",
                "Denies microphone access to every app except the phone dialer.",
                "No app can record audio; only phone calls use the mic.",
                "Off if you use voice notes, voice search or recording apps."),
            Feature(Warden.T_BLOCK_DOWNLOADS, "Block downloads & file saving",
                "Blocks browser downloads and denies storage access to apps (except camera/gallery/WhatsApp); blocks USB file transfer.",
                "Nothing can be saved out of the browser or apps to shared storage.",
                "Off if you need to download or save files."),
            Feature(Warden.T_NO_SCREEN_CAPTURE, "Disable screenshots & recording",
                "Blocks screenshots and screen recording across the whole device.",
                "Prevents capture of any on-screen content.",
                "Off if you need to take screenshots.")
        )),
        Section("System hardening", listOf(
            Feature(Warden.T_DISABLE_SAFEBOOT, "Disable Safe Mode",
                "Prevents booting into Safe Mode.",
                "Stops anyone starting the phone with your policies disabled.",
                "Off if you need Safe Mode to troubleshoot."),
            Feature(Warden.T_BLOCK_USER_CA, "Block user CA certificates",
                "Prevents installing user root certificates and scans for apps that might need one.",
                "Blocks the main way HTTPS traffic gets intercepted (rogue root cert).",
                "Off only if a trusted app legitimately needs a private CA.",
                editor = "certscan"),
            Feature(Warden.T_PASSWORD_HARDENING, "Password hardening",
                "Requires a high-complexity screen lock, 60-second auto-lock, and wipes after 10 failed attempts.",
                "Makes the lock screen genuinely hard to defeat.",
                "Off if you want a simpler lock (less secure)."),
            Feature(Warden.T_SETTINGS_LOCKDOWN, "Lock Settings",
                "Blocks factory reset, adding/removing users and app force-stop from the Settings app.",
                "Stops accidental or malicious changes to core device settings.",
                "Off if you need to reset or add users."),
            Feature(Warden.T_APP_SCREENSHOT_GUARD, "Protect this app's screen",
                "Stops this MDM screen being screenshotted, recorded or cast.",
                "Keeps your policy configuration private (default on).",
                "Off if you need to screenshot this screen for reference.")
        ))
    )
}
