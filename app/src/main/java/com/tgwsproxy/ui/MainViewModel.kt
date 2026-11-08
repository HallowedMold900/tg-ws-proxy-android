package com.tgwsproxy.ui

import com.tgwsproxy.R
import com.tgwsproxy.S
import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.tgwsproxy.ProxyState
import com.tgwsproxy.ProxyStatus
import com.tgwsproxy.data.Draft
import com.tgwsproxy.data.SettingsStore
import com.tgwsproxy.data.generateSecret
import com.tgwsproxy.service.ProxyService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val _draft = MutableStateFlow(store.load())
    val draft: StateFlow<Draft> = _draft.asStateFlow()

    /** Вызывается при каждом новом открытии приложения (не при повороте экрана). */
    fun autostartIfNeeded() {
        val d = _draft.value
        if (d.autostart && ProxyState.status.value is ProxyStatus.Stopped && d.validate().settings != null) {
            start()
        }
    }

    fun update(transform: (Draft) -> Draft) {
        val n = transform(_draft.value)
        _draft.value = n
        store.save(n)
    }

    fun regenerateSecret() = update { it.copy(secret = generateSecret()) }

    /** Запуск или перезапуск с текущими настройками. */
    fun start() {
        if (_draft.value.validate().settings == null) return
        store.save(_draft.value)
        ProxyState.status.value = ProxyStatus.Starting
        val app = getApplication<Application>()
        try {
            ContextCompat.startForegroundService(app, ProxyService.startIntent(app))
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            ProxyState.log(S.get(R.string.err_service_start, msg))
            ProxyState.status.value = ProxyStatus.Error(S.get(R.string.err_service_start, msg))
        }
    }

    fun stop() {
        val app = getApplication<Application>()
        try {
            app.startService(ProxyService.stopIntent(app))
        } catch (e: Throwable) {
            ProxyState.log(S.get(R.string.err_service_stop, e.message ?: e.javaClass.simpleName))
            ProxyState.status.value = ProxyStatus.Stopped
        }
    }

    fun disconnectClient(ip: String) = ProxyService.disconnectClient(ip)

    fun blockClient(ip: String) {
        com.tgwsproxy.stats.ClientRegistry.block(ip)
        ProxyService.disconnectClient(ip)
    }

    fun unblockAll() = com.tgwsproxy.stats.ClientRegistry.unblockAll()

    fun dismissCrash() {
        ProxyState.lastCrash.value = null
        try { java.io.File(getApplication<Application>().filesDir, com.tgwsproxy.App.CRASH_FILE).delete() } catch (_: Throwable) {}
    }
}
