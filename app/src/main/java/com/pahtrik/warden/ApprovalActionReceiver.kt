package com.pahtrik.warden

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra("pkg") ?: return
        val approve = intent.getStringExtra("verb") == "approve"
        Warden.resolvePending(context, pkg, approve)
        AuditLog.add(context, "APP ${if (approve) "APPROVED" else "DENIED"}: $pkg")
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(pkg.hashCode())
    }
}
