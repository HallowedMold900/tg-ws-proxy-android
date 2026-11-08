package com.tgwsproxy.service

import com.tgwsproxy.S
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.tgwsproxy.MainActivity
import com.tgwsproxy.ProxyState
import com.tgwsproxy.ProxyStatus
import com.tgwsproxy.R
import com.tgwsproxy.SpeedPoint
import com.tgwsproxy.data.SettingsStore
import com.tgwsproxy.proxy.ProxyServer
import com.tgwsproxy.util.Fmt
import com.tgwsproxy.util.NetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val networkRestart = Runnable { restartForNetwork() }
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var baseNetwork: String? = null
    @Volatile private var runningLabel: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, S.get(R.string.channel_proxy), NotificationManager.IMPORTANCE_LOW),
        )
        registerNetworkCallback()
        // Скорость и число соединений — прямо в уведомлении
        scope.launch { ProxyState.speed.collect { publishSpeed(it) } }
        // Виджет на рабочем столе повторяет состояние прокси
        scope.launch { ProxyState.status.collect { ProxyWidget.updateAll(applicationContext) } }
        // Проверка обновлений раз в час (реально — не чаще раза в 6 часов)
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try { com.tgwsproxy.update.UpdateChecker.checkIfDue(applicationContext) } catch (_: Throwable) {}
                delay(60 * 60 * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelRestart(this)
            handler.removeCallbacks(networkRestart)
            generation.incrementAndGet()
            ProxyState.status.value = ProxyStatus.Stopped
            ProxyState.runningConfig.value = null
            // Остановка сервера (закрытие сокетов) — только вне главного потока
            worker.execute { stopServer() }
            ProxyWidget.updateAll(applicationContext)
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }

        enterForeground(S.get(R.string.starting))
        if (!startProxy()) {
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Читает сохранённые настройки и (пере)запускает сервер в очереди управления. */
    private fun startProxy(): Boolean {
        val gen = generation.incrementAndGet()
        val draft = SettingsStore(this).load()
        val validation = draft.validate()
        val settings = validation.settings
        if (settings == null) {
            ProxyState.status.value = ProxyStatus.Error(validation.errors.values.first())
            return false
        }

        ProxyState.status.value = ProxyStatus.Starting
        worker.execute {
            // Если после этого запроса пришёл «Стоп» или новый «Старт» — этот запуск уже не нужен
            if (generation.get() != gen) return@execute
            stopServer()
            try {
                val s = ProxyServer(settings)
                s.start()
                if (generation.get() != gen) {
                    s.stop()
                    return@execute
                }
                server = s
                runningLabel = "${settings.host}:${settings.port}"
                ProxyState.runningConfig.value = draft
                ProxyState.status.value = ProxyStatus.Running(settings.host, settings.port, settings.link)
                ProxyState.lanIp.value = NetUtil.lanIpv4(this)
                try { notify(S.get(R.string.running)) } catch (_: Exception) {}
            } catch (e: Throwable) {
                val msg = e.message ?: e.javaClass.simpleName
                ProxyState.log(S.get(R.string.log_start_error, msg))
                if (generation.get() == gen) {
                    ProxyState.status.value = ProxyStatus.Error(S.get(R.string.err_start_failed, msg))
                    ProxyState.runningConfig.value = null
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                    stopSelf()
                }
            }
        }
        return true
    }

    // ───────────────────────── смена сети ─────────────────────────

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val key = network.toString()
                val prev = baseNetwork
                baseNetwork = key
                ProxyState.lanIp.value = NetUtil.lanIpv4(this@ProxyService)
                if (prev != null && prev != key) {
                    handler.removeCallbacks(networkRestart)
                    handler.postDelayed(networkRestart, 1500)
                }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                ProxyState.network.value = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> S.get(R.string.net_cellular)
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    else -> S.get(R.string.net_other)
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                ProxyState.lanIp.value = NetUtil.lanIpv4(this@ProxyService)
            }

            override fun onLost(network: Network) {
                ProxyState.network.value = null
                ProxyState.lanIp.value = null
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Throwable) {
        }
    }

    private fun restartForNetwork() {
        try {
            val status = ProxyState.status.value
            if (status !is ProxyStatus.Running && status !is ProxyStatus.Starting) return
            if (!SettingsStore(this).load().restartOnNetwork) return
            ProxyState.log(S.get(R.string.log_network_changed, ProxyState.network.value ?: S.get(R.string.net_none)))
            startProxy()
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────── уведомление ─────────────────────────

    private fun publishSpeed(points: List<SpeedPoint>) {
        if (ProxyState.status.value !is ProxyStatus.Running) return
        val last = points.lastOrNull() ?: return
        val active = ProxyState.stats.value.active
        try {
            notify(S.get(R.string.notif_speed, Fmt.speed(last.down), Fmt.speed(last.up), active))
        } catch (_: Exception) {
        }
    }

    /** Приложение смахнули из недавних: если включён автозапуск, просим систему поднять прокси снова. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        try {
            if (ProxyState.status.value is ProxyStatus.Running && SettingsStore(this).load().autostart) {
                scheduleRestart(this)
            }
        } catch (_: Throwable) {}
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(networkRestart)
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } catch (_: Throwable) {}
        }
        netCallback = null
        scope.cancel()
        val gen = generation.get()
        worker.execute { if (generation.get() == gen) stopServer() }
        if (ProxyState.status.value is ProxyStatus.Running) {
            ProxyState.status.value = ProxyStatus.Stopped
            ProxyState.runningConfig.value = null
        }
        ProxyWidget.updateAll(applicationContext)
        super.onDestroy()
    }

    private fun enterForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("TG WS Proxy")
            .setContentText(text)
            .setSubText(runningLabel.ifEmpty { null })
            .setContentIntent(open)
            .addAction(0, S.get(R.string.stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "com.tgwsproxy.START"
        const val ACTION_STOP = "com.tgwsproxy.STOP"
        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1

        /** Все запуски и остановки сервера идут строго по очереди в одном потоке. */
        private val worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "tgws-control").apply { isDaemon = true }
        }
        private val generation = AtomicInteger(0)
        @Volatile private var server: ProxyServer? = null

        /** Закрывает все соединения с указанного адреса (вне главного потока). */
        fun disconnectClient(ip: String) {
            worker.execute { try { server?.disconnectClient(ip) } catch (_: Throwable) {} }
        }

        private fun stopServer() {
            val s = server
            server = null
            try { s?.stop() } catch (_: Throwable) {}
        }

        private fun restartPendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 2, Intent(context, RestartReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun scheduleRestart(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val pi = restartPendingIntent(context)
            val at = SystemClock.elapsedRealtime() + 2_000
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }

        fun cancelRestart(context: Context) {
            try { context.getSystemService(AlarmManager::class.java)?.cancel(restartPendingIntent(context)) } catch (_: Throwable) {}
        }

        fun startIntent(context: Context): Intent =
            Intent(context, ProxyService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, ProxyService::class.java).setAction(ACTION_STOP)
    }
}
