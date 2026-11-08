package com.tgwsproxy.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.tgwsproxy.R
import com.tgwsproxy.service.ProxyTileService

@RequiresApi(33)
fun requestAddTile(ctx: Context, onResult: (String) -> Unit) {
    val manager = ctx.getSystemService(StatusBarManager::class.java)
    if (manager == null) {
        onResult("Не удалось добавить плитку")
        return
    }
    manager.requestAddTileService(
        ComponentName(ctx, ProxyTileService::class.java),
        "TG WS Proxy",
        Icon.createWithResource(ctx, R.drawable.ic_stat_proxy),
        ContextCompat.getMainExecutor(ctx),
    ) { code ->
        onResult(
            when (code) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Плитка добавлена"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Плитка уже добавлена"
                else -> "Плитку добавить не удалось"
            },
        )
    }
}
