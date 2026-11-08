package com.tgwsproxy.stats

import android.content.Context
import android.content.SharedPreferences
import com.tgwsproxy.proxy.RouteKind
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

data class DayStats(
    val date: String,
    val ws: Long = 0,
    val cf: Long = 0,
    val worker: Long = 0,
    val tcp: Long = 0,
    val failed: Long = 0,
    val up: Long = 0,
    val down: Long = 0,
) {
    val total: Long get() = ws + cf + worker + tcp
}

/** Статистика по дням (хранится 7 дней). */
object DailyStats {
    private const val KEEP_DAYS = 7
    private var prefs: SharedPreferences? = null
    private val data = LinkedHashMap<String, DayStats>()
    private var dirty = false
    private var ticks = 0

    val days = MutableStateFlow<List<DayStats>>(emptyList())

    @Synchronized
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("daily_stats", Context.MODE_PRIVATE)
        val raw = prefs?.getString("days", "").orEmpty()
        for (line in raw.lines()) {
            val p = line.split('|')
            if (p.size != 8) continue
            try {
                data[p[0]] = DayStats(p[0], p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong(), p[5].toLong(), p[6].toLong(), p[7].toLong())
            } catch (_: NumberFormatException) {
            }
        }
        publish()
    }

    fun today(): String = LocalDate.now().toString()

    @Synchronized
    private fun edit(f: (DayStats) -> DayStats) {
        val d = today()
        data[d] = f(data[d] ?: DayStats(d))
        dirty = true
        if (data.size > KEEP_DAYS) {
            data.keys.sortedDescending().drop(KEEP_DAYS).forEach { data.remove(it) }
        }
        publish()
    }

    fun addRoute(kind: RouteKind) = edit {
        when (kind) {
            RouteKind.WS -> it.copy(ws = it.ws + 1)
            RouteKind.CF -> it.copy(cf = it.cf + 1)
            RouteKind.WORKER -> it.copy(worker = it.worker + 1)
            RouteKind.TCP -> it.copy(tcp = it.tcp + 1)
        }
    }

    fun addFailed() = edit { it.copy(failed = it.failed + 1) }

    fun addTraffic(up: Long, down: Long) {
        if (up == 0L && down == 0L) return
        edit { it.copy(up = it.up + up, down = it.down + down) }
    }

    /** Раз в секунду; запись на диск примерно раз в 10 секунд. */
    @Synchronized
    fun tick() {
        ticks++
        if (ticks % 10 == 0) flush()
    }

    @Synchronized
    fun flush() {
        if (!dirty) return
        val text = data.values.joinToString("\n") {
            "${it.date}|${it.ws}|${it.cf}|${it.worker}|${it.tcp}|${it.failed}|${it.up}|${it.down}"
        }
        prefs?.edit()?.putString("days", text)?.apply()
        dirty = false
    }

    private fun publish() {
        days.value = data.values.sortedByDescending { it.date }
    }
}
