package com.pahtrik.warden

import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import java.util.Calendar

/**
 * Polls foreground usage and suspends any app that has exceeded its daily
 * limit. Limits reset at local midnight. Runs as a lightweight started
 * service kicked by AlarmManager / boot; each tick reschedules itself.
 */
class UsageGuardService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Warden.getToggle(this, Warden.T_TIME_LIMITS)) enforce()
        stopSelf()
        return START_NOT_STICKY
    }

    private fun enforce() {
        if (!Warden.isDeviceOwner(this)) return
        val limitMs = Warden.sessionLimitMin(this) * 60_000L
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()

        // Look back over a window a bit longer than the limit to find the start
        // of the currently-foreground app's continuous session.
        val since = now - limitMs - 30 * 60_000L
        val events = usm.queryEvents(since, now)
        val e = android.app.usage.UsageEvents.Event()

        // Track, per package, the timestamp it last moved to foreground and
        // whether it has since gone to background (session ended).
        val fgStart = HashMap<String, Long>()
        var currentFg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (fgStart[e.packageName] == null) fgStart[e.packageName] = e.timeStamp
                    currentFg = e.packageName
                }
                android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED -> {
                    // Session ended → reset accumulation for that package
                    fgStart.remove(e.packageName)
                    if (currentFg == e.packageName) currentFg = null
                }
            }
        }

        // First, lift any session-suspended app whose cooldown has elapsed,
        // so it can be opened again for a fresh session.
        Warden.sweepSessionCooldowns(this, now)

        val pkg = currentFg ?: return
        if (pkg in Warden.TIME_EXEMPT) return
        if (pkg !in Warden.whitelist(this)) return
        val start = fgStart[pkg] ?: return
        val openMs = now - start
        if (openMs >= limitMs) {
            val d = Warden.dpm(this); val a = Warden.admin(this)
            d.setPackagesSuspended(a, arrayOf(pkg), true)
            Warden.recordSessionSuspend(this, pkg, now)
            // Kick the user to the home screen so the closed app leaves foreground
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            AuditLog.add(this, "SESSION LIMIT: $pkg open ${openMs / 60000} min — closed")
        }
    }

    companion object {
        /** On boot, clear any lingering session suspensions. */
        fun resetDay(ctx: Context) {
            if (!Warden.isDeviceOwner(ctx)) return
            Warden.sweepSessionCooldowns(ctx, System.currentTimeMillis(), force = true)
        }
    }
}
