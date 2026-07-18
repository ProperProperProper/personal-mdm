package com.pahtrik.warden

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Watches for new/updated packages and boot. New non-whitelisted apps are
 * immediately suspended and surfaced as an Approve/Deny notification.
 */
class PackageEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                AuditLog.add(context, "BOOT")
                if (Warden.getToggle(context, Warden.T_ENFORCE_WHITELIST))
                    Warden.enforceWhitelist(context)
                if (Warden.getToggle(context, Warden.T_TIME_LIMITS)) {
                    UsageGuardService.resetDay(context)
                    Warden.scheduleUsageGuard(context)
                }
                return
            }
            Intent.ACTION_PACKAGE_ADDED, Intent.ACTION_PACKAGE_REPLACED -> {
                val pkg = intent.data?.schemeSpecificPart ?: return
                val isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                if (pkg == context.packageName) return
                if (pkg in Warden.whitelist(context)) return
                if (isUpdate && intent.action == Intent.ACTION_PACKAGE_REPLACED) return
                if (!Warden.getToggle(context, Warden.T_GATE_NEW_APPS)) return

                // Quarantine first, ask questions after
                if (Warden.isDeviceOwner(context)) {
                    Warden.dpm(context).setPackagesSuspended(
                        Warden.admin(context), arrayOf(pkg), true
                    )
                }
                Warden.addPending(context, pkg)
                AuditLog.add(context, "APP INSTALLED (quarantined): $pkg")
                notifyApproval(context, pkg)
            }
        }
    }

    private fun notifyApproval(ctx: Context, pkg: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                Warden.CHANNEL_APPROVALS, "App approvals",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        val label = try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            ctx.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { pkg }

        fun action(verb: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, (pkg + verb).hashCode(),
            Intent(ctx, ApprovalActionReceiver::class.java)
                .putExtra("pkg", pkg).putExtra("verb", verb),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = Notification.Builder(ctx, Warden.CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_warden)
            .setContentTitle("New app detected: $label")
            .setContentText("$pkg was installed and has been quarantined.")
            .addAction(Notification.Action.Builder(null, "Approve", action("approve")).build())
            .addAction(Notification.Action.Builder(null, "Deny", action("deny")).build())
            .setOngoing(true)
            .build()
        nm.notify(pkg.hashCode(), n)
    }
}
