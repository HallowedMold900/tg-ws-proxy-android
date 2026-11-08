package com.tgwsproxy.service

import com.tgwsproxy.S
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.tgwsproxy.MainActivity
import com.tgwsproxy.ProxyState
import com.tgwsproxy.ProxyStatus
import com.tgwsproxy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Плитка в шторке: включить / выключить прокси одним нажатием. */
class ProxyTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch { ProxyState.status.collect { refresh(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val status = ProxyState.status.value
        try {
            if (status is ProxyStatus.Running || status is ProxyStatus.Starting) {
                startService(ProxyService.stopIntent(this))
            } else {
                ProxyState.status.value = ProxyStatus.Starting
                ContextCompat.startForegroundService(this, ProxyService.startIntent(this))
            }
        } catch (_: Throwable) {
            // Система не дала запустить сервис из плитки — открываем приложение
            openApp()
        }
    }

    private fun refresh(status: ProxyStatus) {
        val tile = qsTile ?: return
        val (state, subtitle) = when (status) {
            is ProxyStatus.Running -> Tile.STATE_ACTIVE to S.get(R.string.running)
            ProxyStatus.Starting -> Tile.STATE_ACTIVE to S.get(R.string.starting)
            is ProxyStatus.Error -> Tile.STATE_INACTIVE to S.get(R.string.error)
            ProxyStatus.Stopped -> Tile.STATE_INACTIVE to S.get(R.string.tile_off)
        }
        tile.state = state
        tile.label = "TG WS Proxy"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_proxy)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
