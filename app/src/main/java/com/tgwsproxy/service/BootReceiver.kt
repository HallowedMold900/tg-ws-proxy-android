package com.tgwsproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tgwsproxy.data.SettingsStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val draft = SettingsStore(context).load()
        if (draft.autostart && draft.validate().settings != null) {
            try {
                ContextCompat.startForegroundService(context, ProxyService.startIntent(context))
            } catch (_: Throwable) {
            }
        }
    }
}
