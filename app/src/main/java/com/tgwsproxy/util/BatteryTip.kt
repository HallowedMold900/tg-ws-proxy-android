package com.tgwsproxy.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Подсказка «Без ограничений по батарее»: система иначе может остановить прокси в фоне. */
object BatteryTip {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("tips", Context.MODE_PRIVATE)

    fun isIgnoring(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true
    } catch (_: Exception) {
        true
    }

    fun dismissed(ctx: Context): Boolean = prefs(ctx).getBoolean("batteryDismissed", false)

    fun dismiss(ctx: Context) {
        prefs(ctx).edit().putBoolean("batteryDismissed", true).apply()
    }

    /** Системный диалог «Разрешить работу без ограничений»; запасной путь — общий список. */
    fun request(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
            )
        } catch (_: Exception) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    /** Страница приложения в настройках (там же «Автозапуск» и «Экономия заряда» на Xiaomi и др.). */
    fun openAppSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
            )
        } catch (_: Exception) {
        }
    }
}
