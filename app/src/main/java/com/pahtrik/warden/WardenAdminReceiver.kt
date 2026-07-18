package com.pahtrik.warden

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class WardenAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        // Re-apply policy whenever admin/device-owner state is (re)granted
        if (Warden.isDeviceOwner(context) && Warden.getToggle(context, Warden.T_ENFORCE_WHITELIST)) {
            Warden.enforceWhitelist(context)
        }
    }
}
