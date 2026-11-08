package com.tgwsproxy.stats

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class ClientInfo(
    val ip: String,
    val active: Int,
    val total: Long,
    val up: Long,
    val down: Long,
    val lastSeen: Long,
) {
    val isLocal: Boolean get() = ip == "127.0.0.1" || ip == "0:0:0:0:0:0:0:1" || ip == "::1"
}

/** Подключённые к прокси устройства и список запрещённых адресов. */
object ClientRegistry {
    private class Entry {
        val active = AtomicInteger(0)
        val total = AtomicLong(0)
        val up = AtomicLong(0)
        val down = AtomicLong(0)
        @Volatile var lastSeen = 0L
    }

    private val map = ConcurrentHashMap<String, Entry>()
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private var prefs: SharedPreferences? = null

    val clients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val blockedIps = MutableStateFlow<Set<String>>(emptySet())

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("clients", Context.MODE_PRIVATE)
        blocked.addAll(prefs?.getStringSet("blocked", emptySet()).orEmpty())
        blockedIps.value = blocked.toSet()
    }

    fun isBlocked(ip: String): Boolean = ip in blocked

    fun block(ip: String) {
        blocked += ip
        persist()
    }

    fun unblockAll() {
        blocked.clear()
        persist()
    }

    private fun persist() {
        blockedIps.value = blocked.toSet()
        prefs?.edit()?.putStringSet("blocked", blocked.toSet())?.apply()
    }

    fun open(ip: String) {
        val e = map.getOrPut(ip) { Entry() }
        e.active.incrementAndGet()
        e.total.incrementAndGet()
        e.lastSeen = System.currentTimeMillis()
    }

    fun close(ip: String) {
        val e = map[ip] ?: return
        if (e.active.decrementAndGet() < 0) e.active.set(0)
        e.lastSeen = System.currentTimeMillis()
    }

    fun addUp(ip: String, n: Long) { map[ip]?.up?.addAndGet(n) }
    fun addDown(ip: String, n: Long) { map[ip]?.down?.addAndGet(n) }

    fun reset() {
        map.clear()
        clients.value = emptyList()
    }

    /** Раз в секунду из сэмплера. Устройства, не появлявшиеся 10 минут, скрываются. */
    fun publish() {
        val now = System.currentTimeMillis()
        clients.value = map.entries
            .map { (ip, e) -> ClientInfo(ip, e.active.get(), e.total.get(), e.up.get(), e.down.get(), e.lastSeen) }
            .filter { it.active > 0 || now - it.lastSeen < 10 * 60_000L }
            .sortedWith(compareByDescending<ClientInfo> { it.active > 0 }.thenByDescending { it.lastSeen })
    }
}
