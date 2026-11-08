package com.tgwsproxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tgwsproxy.ProxyState
import com.tgwsproxy.ProxyStatus
import com.tgwsproxy.data.SettingsStore

/** Срабатывает после смахивания приложения, если система остановила прокси. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val status = ProxyState.status.value
            if (status is ProxyStatus.Running || status is ProxyStatus.Starting) return
            val draft = SettingsStore(context).load()
            if (draft.autostart && draft.validate().settings != null) {
                ContextCompat.startForegroundService(context, ProxyService.startIntent(context))
            }
        } catch (_: Throwable) {
            // Android может запретить запуск из фона — тогда прокси запустится при открытии приложения
        }
    }
}
