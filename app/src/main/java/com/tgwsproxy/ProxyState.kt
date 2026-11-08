package com.tgwsproxy

import com.tgwsproxy.data.Draft
import com.tgwsproxy.stats.DailyStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong
import java.time.format.DateTimeFormatter

sealed interface ProxyStatus {
    data object Stopped : ProxyStatus
    data object Starting : ProxyStatus
    data class Running(val host: String, val port: Int, val link: String) : ProxyStatus
    data class Error(val message: String) : ProxyStatus
}

data class ProxyStats(
    val active: Int = 0,
    val total: Long = 0,
    val ws: Long = 0,
    val cf: Long = 0,
    val worker: Long = 0,
    val tcp: Long = 0,
    val failed: Long = 0,
    val rejected: Long = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
)

/** Скорость за последнюю секунду, байт/с. */
data class SpeedPoint(val up: Long, val down: Long)

/** Общее состояние между сервисом и интерфейсом (один процесс). */
object ProxyState {
    val status = MutableStateFlow<ProxyStatus>(ProxyStatus.Stopped)
    val stats = MutableStateFlow(ProxyStats())
    val logs = MutableStateFlow<List<String>>(emptyList())
    val runningConfig = MutableStateFlow<Draft?>(null)
    val lastCrash = MutableStateFlow<String?>(null)

    /** Локальный IP в Wi-Fi и название текущей сети (обновляет сервис). */
    val lanIp = MutableStateFlow<String?>(null)
    val network = MutableStateFlow<String?>(null)

    /** Автозапуск при открытии приложения уже обработан в этом процессе. */
    @Volatile var launchHandled = false

    const val SPEED_POINTS = 60
    val speed = MutableStateFlow<List<SpeedPoint>>(emptyList())

    private val upTotal = AtomicLong(0)
    private val downTotal = AtomicLong(0)
    private var lastUp = 0L
    private var lastDown = 0L

    fun addUp(n: Long) { upTotal.addAndGet(n) }
    fun addDown(n: Long) { downTotal.addAndGet(n) }

    @Synchronized
    fun resetTraffic() {
        upTotal.set(0)
        downTotal.set(0)
        lastUp = 0
        lastDown = 0
        speed.value = emptyList()
        stats.value = ProxyStats()
        com.tgwsproxy.stats.ClientRegistry.reset()
    }

    /** Вызывается раз в секунду из ProxyServer. */
    @Synchronized
    fun sampleTraffic() {
        val u = upTotal.get()
        val d = downTotal.get()
        val point = SpeedPoint(up = u - lastUp, down = d - lastDown)
        lastUp = u
        lastDown = d
        speed.update { (it + point).takeLast(SPEED_POINTS) }
        DailyStats.addTraffic(point.up, point.down)
        DailyStats.tick()
        com.tgwsproxy.stats.ClientRegistry.publish()
        stats.update { it.copy(bytesUp = u, bytesDown = d) }
    }

    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    private var lastMsg: String? = null
    private var lastCount = 1

    /** Одинаковые сообщения подряд склеиваются в одну строку с «×N». */
    @Synchronized
    fun log(msg: String) {
        val time = LocalTime.now().format(fmt)
        if (msg == lastMsg) {
            lastCount++
            val line = "$time  $msg (×$lastCount)"
            logs.update { if (it.isEmpty()) listOf(line) else it.dropLast(1) + line }
        } else {
            lastMsg = msg
            lastCount = 1
            val line = "$time  $msg"
            logs.update { (it + line).takeLast(200) }
        }
    }

    fun clearLogs() {
        logs.value = emptyList()
    }

    fun updateStats(f: (ProxyStats) -> ProxyStats) = stats.update(f)
}
