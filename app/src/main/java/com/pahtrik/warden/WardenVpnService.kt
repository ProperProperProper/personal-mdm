package com.pahtrik.warden

import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * Blocking-only firewall sinkhole. Apps NOT allowed internet are routed into
 * this tunnel, whose traffic is never read or forwarded — it just dies.
 * Allowed apps are excluded from the tunnel and use the network normally.
 * No INTERNET permission required: nothing is ever forwarded.
 */
class WardenVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        establish()
        return START_STICKY
    }

    private fun establish() {
        tun?.close()
        val builder = Builder()
            .setSession("Personal MDM Firewall")
            .addAddress("10.111.222.1", 32)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .setBlocking(false)
        // Allowed apps bypass the sinkhole entirely
        for (pkg in Warden.netAllowed(this)) {
            runCatching { builder.addDisallowedApplication(pkg) }
        }
        tun = builder.establish()
    }

    override fun onRevoke() {
        tun?.close(); tun = null
        super.onRevoke()
    }

    override fun onDestroy() {
        tun?.close(); tun = null
        super.onDestroy()
    }
}
