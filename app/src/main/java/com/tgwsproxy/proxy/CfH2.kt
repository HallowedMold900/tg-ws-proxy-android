package com.tgwsproxy.proxy

import android.os.SystemClock
import com.tgwsproxy.ProxyState
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Ошибка транспорта MTProto: код нужно отдать клиенту Telegram, а не просто закрыть соединение. */
class H2TransportError(val code: Int) : IOException("MTProto transport error $code")

/**
 * HTTP/2 для медиа через CF-прокси (упрощённый порт proxy/cf_h2.py).
 * Пакеты клиента уходят как POST https://kwsN.<домен>/api, ответы читаются из тела.
 * Чтобы получать данные, которые сервер отдаёт только в ответ на запрос, последние пакеты
 * повторяются («replay») с теми же ограничениями по времени и числу попыток, что и в оригинале.
 */
object CfH2 {
    const val MAX_PACKET = 4 * 1024 * 1024
    const val MAX_PLAIN_PADDING = 256
    const val MAX_CHANNEL_REQUESTS = 8
    const val REQUEST_TIMEOUT_S = 65L
    const val DOMAIN_COOLDOWN_MS = 30_000L
    const val SETUP_TIMEOUT_MS = 8_000L
    const val REPLAY_IDLE_MS = 1_000L
    const val REPLAY_REQUEST_MS = 3_000L
    const val REPLAY_CHECK_MS = 250L
    const val REPLAY_MAX_AGE_MS = 30_000L
    const val REPLAY_HISTORY_BYTES = 64 * 1024
    const val REPLAY_HISTORY_PACKETS = 16
    const val MAX_CHANNEL_REPLAYS = 2
    const val REPLAY_RETRY_MS = 2_000L
    const val REPLAY_MAX_ATTEMPTS = 3

    val OCTET = "application/octet-stream".toMediaType()

    val executor = Executors.newCachedThreadPool { r -> Thread(r, "tgws-h2").apply { isDaemon = true } }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(REQUEST_TIMEOUT_S + 5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .build()
    }

    fun le32(b: ByteArray, off: Int = 0): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    fun putLe32(v: Int): ByteArray =
        byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}

class CfH2Pool {
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val setupRetryAfter = ConcurrentHashMap<Int, Long>()
    private val ready = ConcurrentHashMap.newKeySet<String>()

    fun open(dc: Int, baseDomains: List<String>, proto: Proto, tag: String): H2Upstream? {
        val started = SystemClock.elapsedRealtime()
        if (started < (setupRetryAfter[dc] ?: 0L)) return null
        for (base in baseDomains) {
            if (SystemClock.elapsedRealtime() - started > CfH2.SETUP_TIMEOUT_MS) break
            val host = "kws$dc.$base"
            if (SystemClock.elapsedRealtime() < (failedUntil[host] ?: 0L)) continue
            if (host !in ready) {
                try {
                    preflight(host)
                    ready += host
                } catch (e: Exception) {
                    failedUntil[host] = SystemClock.elapsedRealtime() + CfH2.DOMAIN_COOLDOWN_MS
                    ProxyState.log("$tag: H2 preflight $host — ${e.message ?: e.javaClass.simpleName}")
                    continue
                }
            }
            return H2Upstream(host, proto) { markFailed(it) }
        }
        setupRetryAfter[dc] = SystemClock.elapsedRealtime() + CfH2.DOMAIN_COOLDOWN_MS
        return null
    }

    fun markFailed(host: String) {
        ready -= host
        failedUntil[host] = SystemClock.elapsedRealtime() + CfH2.DOMAIN_COOLDOWN_MS
    }

    private fun preflight(host: String) {
        val c = CfH2.client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        val req = Request.Builder().url("https://$host/api").head().build()
        c.newCall(req).execute().use { resp ->
            if (resp.protocol != Protocol.HTTP_2) throw IOException("согласован ${resp.protocol}, нужен HTTP/2")
            val code = resp.code
            if (code !in 200..299 && code != 405 && code != 501) throw IOException("HTTP $code")
        }
    }
}

class H2Upstream(
    val host: String,
    private val proto: Proto,
    private val onFailure: (String) -> Unit,
) : Upstream {
    override val kind = RouteKind.CF
    override val splitPackets = false
    override val plainMode = true

    private class Req(val body: ByteArray, val replay: Boolean, val packet: Replay?) {
        @Volatile var sentAt = 0L
        @Volatile var receiving = false
        @Volatile var call: Call? = null
    }

    private class Replay(val sentAt: Long, val body: ByteArray, val sequence: Long) {
        var attempts = 0
        var lastAttempt = 0L
        @Volatile var pendingReq: Req? = null
        val originals: MutableSet<Req> = ConcurrentHashMap.newKeySet()
        @Volatile var retired = false
        @Volatile var replyId: ByteArray? = null
        val replayed: Boolean get() = lastAttempt > 0 || attempts > 0
    }

    private val closed = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Any>(64)
    private val inFlight = Semaphore(CfH2.MAX_CHANNEL_REQUESTS)
    private val pending: MutableSet<Req> = ConcurrentHashMap.newKeySet()
    private val lock = Any()
    private val history = ArrayList<Replay>()
    private var replayKey: ByteArray? = null
    private var sequence = 0L
    private val rnd = SecureRandom()
    private val end = Any()
    @Volatile private var lastProgress = SystemClock.elapsedRealtime()

    private var buf = ByteArray(64 * 1024)
    private var size = 0
    private val recovery: Thread = Thread({ recoveryLoop() }, "tgws-h2-replay").apply { isDaemon = true; start() }

    // ───────────── вход: поток клиента (уже расшифрованный) ─────────────

    override fun send(data: ByteArray) {
        if (closed.get()) throw IOException("H2: канал закрыт")
        if (size + data.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + data.size))
        System.arraycopy(data, 0, buf, size, data.size)
        size += data.size

        var pos = 0
        while (pos < size) {
            val avail = size - pos
            val hdr: Int
            val length: Int
            if (proto == Proto.ABRIDGED) {
                val first = buf[pos].toInt() and 0xff
                var words = first and 0x7f
                if (words == 0x7f) {
                    if (avail < 4) break
                    words = (buf[pos + 1].toInt() and 0xff) or ((buf[pos + 2].toInt() and 0xff) shl 8) or
                        ((buf[pos + 3].toInt() and 0xff) shl 16)
                    hdr = 4
                } else {
                    hdr = 1
                }
                length = words * 4
            } else {
                if (avail < 4) break
                hdr = 4
                length = CfH2.le32(buf, pos) and 0x7fffffff
            }
            val maxLen = CfH2.MAX_PACKET + if (proto == Proto.PADDED) CfH2.MAX_PLAIN_PADDING else 0
            if (length < 24 || length > maxLen) throw IOException("H2: длина пакета вне диапазона: $length")
            if (avail < hdr + length) break

            var body = buf.copyOfRange(pos + hdr, pos + hdr + length)
            if (proto == Proto.PADDED) {
                val plain = body.take(8).all { it == 0.toByte() }
                val packetLength = if (plain) 20 + CfH2.le32(body, 16) else 24 + ((length - 24) / 16) * 16
                val padding = length - packetLength
                val maxPadding = if (plain) CfH2.MAX_PLAIN_PADDING else 15
                if (packetLength < 24 || packetLength > length || padding > maxPadding) {
                    throw IOException("H2: неверный пакет с выравниванием")
                }
                body = body.copyOf(packetLength)
            }
            if (body.size % 4 != 0 || body.size > CfH2.MAX_PACKET) throw IOException("H2: неверный размер пакета")
            pos += hdr + length
            submit(body, replay = false)
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, size - pos)
            size -= pos
        }
    }

    // ───────────── выход: ответы для клиента Telegram (с транспортным заголовком) ─────────────

    override fun receive(): ByteArray? = try {
        val v = queue.take()
        if (v === end) null else v as ByteArray
    } catch (_: InterruptedException) {
        null
    }

    private fun encodeReply(body: ByteArray): ByteArray {
        if (body.isEmpty() || body.size % 4 != 0 || body.size > CfH2.MAX_PACKET) {
            throw IOException("H2: неверная длина ответа ${body.size}")
        }
        return when (proto) {
            Proto.ABRIDGED -> {
                val words = body.size / 4
                val prefix = if (words < 0x7f) byteArrayOf(words.toByte())
                else byteArrayOf(0x7f, words.toByte(), (words shr 8).toByte(), (words shr 16).toByte())
                prefix + body
            }
            Proto.INTERMEDIATE -> CfH2.putLe32(body.size) + body
            Proto.PADDED -> {
                val padding = ByteArray(rnd.nextInt(4)).also { rnd.nextBytes(it) }
                CfH2.putLe32(body.size + padding.size) + body + padding
            }
        }
    }

    // ───────────── запросы ─────────────

    private fun submit(body: ByteArray, replay: Boolean): Boolean {
        if (closed.get()) throw IOException("H2: канал закрыт")
        if (replay) {
            if (!inFlight.tryAcquire()) return false
        } else if (!inFlight.tryAcquire(CfH2.REQUEST_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw IOException("H2: очередь запросов переполнена")
        }
        val req = prepare(body, replay)
        if (req == null) {
            inFlight.release()
            return false
        }
        CfH2.executor.execute { run(req) }
        return true
    }

    private fun prepare(body: ByteArray, replay: Boolean): Req? = synchronized(lock) {
        if (closed.get()) return null
        val now = SystemClock.elapsedRealtime()
        val packet = if (replay) findPacket(body) else remember(body, now)
        if (replay && (packet == null || packet.pendingReq != null || replayBlocked(packet, now))) return null
        val req = Req(body, replay, packet)
        if (packet != null) {
            if (replay) {
                packet.attempts++
                packet.lastAttempt = now
                packet.pendingReq = req
            } else {
                packet.originals.add(req)
                trim(now)
            }
        }
        pending.add(req)
        req
    }

    private fun run(req: Req) {
        try {
            val reply = post(req)
            lastProgress = SystemClock.elapsedRealtime()
            if (reply.isNotEmpty() && !closed.get()) {
                req.packet?.let { p ->
                    val id = reply.copyOf(minOf(24, reply.size))
                    val old = p.replyId
                    if (old == null || !old.contentEquals(id)) {
                        p.replyId = id
                        p.attempts = 0
                    }
                }
                if (!queue.offer(encodeReply(reply))) throw IOException("H2: очередь ответов переполнена")
            }
        } catch (e: H2TransportError) {
            if (!closed.getAndSet(true)) {
                ProxyState.log("H2 $host: ошибка транспорта ${e.code}, передаю клиенту")
                queue.clear()
                queue.offer(encodeReply(CfH2.putLe32(e.code)))
                queue.offer(end)
                cancelAll()
            }
        } catch (e: Throwable) {
            if (!closed.get()) {
                ProxyState.log("H2 $host: ${e.message ?: e.javaClass.simpleName}")
                onFailure(host)
                shutdown()
            }
        } finally {
            synchronized(lock) {
                pending.remove(req)
                req.packet?.let {
                    it.originals.remove(req)
                    if (it.pendingReq === req) it.pendingReq = null
                }
            }
            inFlight.release()
        }
    }

    private fun post(req: Req): ByteArray {
        val request = Request.Builder()
            .url("https://$host/api")
            .header("Accept-Encoding", "identity")
            .post(req.body.toRequestBody(CfH2.OCTET))
            .build()
        val call = CfH2.client.newCall(request)
        req.call = call
        req.sentAt = SystemClock.elapsedRealtime()
        if (closed.get()) call.cancel()
        call.execute().use { resp ->
            req.receiving = true
            lastProgress = SystemClock.elapsedRealtime()
            if (resp.protocol != Protocol.HTTP_2) throw IOException("CF /api: согласован ${resp.protocol}")
            when (resp.code) {
                403, 404, 429, 444 -> throw H2TransportError(-resp.code)
                200 -> Unit
                else -> throw IOException("CF /api HTTP ${resp.code}")
            }
            if (resp.header("content-type").orEmpty().contains("text/html", ignoreCase = true)) {
                throw IOException("CF /api вернул HTML вместо MTProto")
            }
            val body = resp.body ?: return ByteArray(0)
            if (body.contentLength() > CfH2.MAX_PACKET) throw IOException("ответ слишком большой")
            val bytes = body.bytes()
            if (bytes.size > CfH2.MAX_PACKET) throw IOException("ответ слишком большой")
            if (bytes.isNotEmpty() && bytes.size % 4 != 0) throw IOException("ответ не выровнен")
            if (bytes.size == 4) {
                val code = CfH2.le32(bytes)
                if (code < 0) throw H2TransportError(code)
            }
            return bytes
        }
    }

    // ───────────── повторы (replay) ─────────────

    private fun findPacket(body: ByteArray): Replay? = history.firstOrNull { it.body.contentEquals(body) }

    private fun remember(body: ByteArray, now: Long): Replay? {
        val key = body.copyOf(8)
        if (key.all { it == 0.toByte() }) return null
        val current = replayKey
        if (current == null || !current.contentEquals(key)) {
            history.clear()
            replayKey = key
        }
        if (body.size > CfH2.REPLAY_HISTORY_BYTES) return null
        var p = findPacket(body)
        if (p == null) {
            p = Replay(now, body, ++sequence)
            history.add(p)
        }
        return p
    }

    private fun trim(now: Long) {
        var retained = history.sumOf { it.body.size }
        while (history.size > CfH2.REPLAY_HISTORY_PACKETS || retained > CfH2.REPLAY_HISTORY_BYTES) {
            val victim = history.firstOrNull {
                it.originals.isEmpty() &&
                    !(it.retired && it.attempts < CfH2.REPLAY_MAX_ATTEMPTS && now - it.sentAt <= CfH2.REPLAY_MAX_AGE_MS)
            } ?: history[0]
            history.remove(victim)
            retained -= victim.body.size
        }
    }

    private fun overdue(e: Replay, now: Long): Boolean =
        e.retired || e.originals.any { it.sentAt > 0 && !it.receiving && now - it.sentAt >= CfH2.REPLAY_REQUEST_MS }

    private fun replayLimit(e: Replay): Int =
        if (e.originals.isNotEmpty() || e.retired || e === history.lastOrNull()) CfH2.REPLAY_MAX_ATTEMPTS else 0

    /** true — повтор сейчас не нужен (аналог _replay_blocker). Вызывать под lock. */
    private fun replayBlocked(e: Replay, now: Long): Boolean {
        if (now - e.sentAt !in 0..CfH2.REPLAY_MAX_AGE_MS) return true
        if (e.attempts >= replayLimit(e)) return true
        if (queue.isNotEmpty()) return true
        if (e.originals.any { it.receiving }) return true
        if (e.pendingReq?.receiving == true) return true
        if (e.replayed && now - e.lastAttempt < CfH2.REPLAY_RETRY_MS) return true
        if (overdue(e, now)) return false
        if (e !== history.lastOrNull() || pending.any { it !== e.pendingReq }) return true
        if (e.replayed && now - lastProgress < CfH2.REPLAY_IDLE_MS) return true
        return false
    }

    private fun pickReplay(now: Long): ByteArray? {
        if (pending.count { it.replay } >= CfH2.MAX_CHANNEL_REPLAYS) return null
        val candidates = history.sortedWith(
            compareBy<Replay>({ !overdue(it, now) }, { if (overdue(it, now)) it.sequence else -it.sequence }),
        )
        for (e in candidates) if (!replayBlocked(e, now)) return e.body
        return null
    }

    private fun recoveryLoop() {
        try {
            while (!closed.get()) {
                Thread.sleep(CfH2.REPLAY_CHECK_MS)
                if (closed.get()) break
                val body = synchronized(lock) { pickReplay(SystemClock.elapsedRealtime()) } ?: continue
                try { submit(body, replay = true) } catch (_: IOException) {}
            }
        } catch (_: InterruptedException) {
        }
    }

    // ───────────── закрытие ─────────────

    private fun cancelAll() {
        pending.forEach { try { it.call?.cancel() } catch (_: Throwable) {} }
    }

    private fun shutdown() {
        closed.set(true)
        if (!queue.offer(end)) {
            queue.clear()
            queue.offer(end)
        }
        cancelAll()
        recovery.interrupt()
    }

    override fun close() {
        closed.set(true)
        if (!queue.offer(end)) {
            queue.clear()
            queue.offer(end)
        }
        cancelAll()
        recovery.interrupt()
    }
}
