package com.tgwsproxy.ui

import com.tgwsproxy.S
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
        onResult(S.get(R.string.tile_add_failed))
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
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> S.get(R.string.tile_added)
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> S.get(R.string.tile_already)
                else -> S.get(R.string.tile_add_failed2)
            },
        )
    }
}
