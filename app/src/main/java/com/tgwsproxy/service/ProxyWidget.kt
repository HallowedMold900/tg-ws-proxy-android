package com.tgwsproxy.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.tgwsproxy.MainActivity
import com.tgwsproxy.ProxyState
import com.tgwsproxy.ProxyStatus
import com.tgwsproxy.R

/** Виджет рабочего стола: статус прокси; нажатие включает или выключает его. */
class ProxyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = build(context)
        for (id in appWidgetIds) manager.updateAppWidget(id, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) toggle(context)
    }

    private fun toggle(context: Context) {
        val status = ProxyState.status.value
        try {
            if (status is ProxyStatus.Running || status is ProxyStatus.Starting) {
                context.startService(ProxyService.stopIntent(context))
            } else {
                ProxyState.status.value = ProxyStatus.Starting
                ContextCompat.startForegroundService(context, ProxyService.startIntent(context))
            }
        } catch (_: Throwable) {
            // Система не дала запустить сервис из виджета — открываем приложение
            try {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {
            }
        }
        updateAll(context)
    }

    companion object {
        const val ACTION_TOGGLE = "com.tgwsproxy.WIDGET_TOGGLE"

        fun updateAll(context: Context) {
            try {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, ProxyWidget::class.java))
                if (ids.isEmpty()) return
                val views = build(context)
                for (id in ids) manager.updateAppWidget(id, views)
            } catch (_: Throwable) {
            }
        }

        private fun build(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_proxy)
            val status = ProxyState.status.value
            val title: String
            val sub: String
            val on: Boolean
            when (status) {
                is ProxyStatus.Running -> { title = "Прокси работает"; sub = "${status.host}:${status.port} · нажмите, чтобы выключить"; on = true }
                ProxyStatus.Starting -> { title = "Запуск…"; sub = "Подождите секунду"; on = true }
                is ProxyStatus.Error -> { title = "Ошибка"; sub = status.message; on = false }
                ProxyStatus.Stopped -> { title = "Прокси выключен"; sub = "Нажмите, чтобы запустить"; on = false }
            }
            views.setTextViewText(R.id.widget_title, title)
            views.setTextViewText(R.id.widget_sub, sub)
            views.setImageViewResource(R.id.widget_icon, if (on) R.drawable.widget_icon_on else R.drawable.widget_icon_off)
            val pi = PendingIntent.getBroadcast(
                context, 4,
                Intent(context, ProxyWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }
    }
}
