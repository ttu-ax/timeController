package com.example.procrastinationgate

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val preferences = GatePreferences(context)
        if (!preferences.monitoringEnabled) return
        if (GateRepository(context).enabledRules().isEmpty()) return
        if (!Settings.canDrawOverlays(context) || !hasUsageAccess(context)) return

        runCatching {
            context.startForegroundService(Intent(context, GateMonitorService::class.java))
        }
    }

    private fun hasUsageAccess(context: Context): Boolean {
        val manager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return manager.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }
}
