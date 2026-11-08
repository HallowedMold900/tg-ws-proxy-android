package com.tgwsproxy

import android.app.Application
import android.util.Log
import java.io.File
import java.util.Date

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        com.tgwsproxy.stats.DailyStats.init(this)
        com.tgwsproxy.stats.ClientRegistry.init(this)
        com.tgwsproxy.update.UpdateChecker.init(this)
        val file = File(filesDir, CRASH_FILE)
        try {
            if (file.exists()) ProxyState.lastCrash.value = file.readText()
        } catch (_: Throwable) {}

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.writeText("${Date()}  поток: ${thread.name}\n" + Log.getStackTraceString(error))
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
