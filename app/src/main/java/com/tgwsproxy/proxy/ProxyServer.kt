package com.tgwsproxy.proxy

import com.tgwsproxy.R
import com.tgwsproxy.S
import com.tgwsproxy.ProxyState
import com.tgwsproxy.data.ProxySettings
import com.tgwsproxy.stats.ClientRegistry
import java.io.OutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.min

enum class RouteKind { WS, CF, WORKER, TCP }

interface Upstream : Closeable {
    val kind: RouteKind
    /** Нужно ли резать клиентский поток на отдельные MTProto-пакеты (по одному на WS-сообщение). */
    val splitPackets: Boolean
    /** HTTP/2-канал: принимает расшифрованные пакеты клиента без своего obfuscated2-рукопожатия. */
    val plainMode: Boolean get() = false
    fun send(data: ByteArray)
    fun receive(): ByteArray?
}

class WsUpstream(private val ws: WsConn, override val kind: RouteKind, override val splitPackets: Boolean) : Upstream {
    override fun send(data: ByteArray) = ws.sendBinary(data)
    override fun receive(): ByteArray? = ws.receive()
    override fun close() = ws.close()
}

class TcpUpstream(private val sock: Socket) : Upstream {
    override val kind = RouteKind.TCP
    override val splitPackets = false
    private val input = sock.getInputStream()
    private val output = sock.getOutputStream()
    private val buf = ByteArray(64 * 1024)

    override fun send(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    override fun receive(): ByteArray? {
        while (true) {
            val n = input.read(buf)
            if (n < 0) return null
            if (n > 0) return buf.copyOf(n)
        }
    }

    override fun close() {
        try { sock.close() } catch (_: Exception) {}
    }
}

/**
 * Локальный MTProto-прокси: Telegram → 127.0.0.1:порт → WebSocket (TLS) → Telegram DC.
 *
 * Порядок маршрутов повторяет оригинал (proxy/pool.py + proxy/bridge.py):
 *  1) прямой WebSocket на kwsN.web.telegram.org через IP из «DC → IP» (с domain fronting как запасом),
 *  2) Cloudflare Worker, 3) CF-прокси, 4) прямой TCP на адрес DC.
 */
class ProxyServer(private val cfg: ProxySettings) {
    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "tgws-conn").apply { isDaemon = true } }
    private val tracked = ConcurrentHashMap.newKeySet<Closeable>()

    private val wsFailures = ConcurrentHashMap<String, Int>()
    private val wsRetryAfter = ConcurrentHashMap<String, Long>()
    private val tcpFailures = ConcurrentHashMap<String, Int>()
    private val tcpRetryAfter = ConcurrentHashMap<String, Long>()
    private val cfSticky = ConcurrentHashMap<Int, String>()
    private val h2Pool = CfH2Pool()

    @Volatile private var stopped = false
    @Volatile private var frontingFirst = false
    @Volatile private var cfDomains: List<String> = CfDomains.fallback()

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName(cfg.host), cfg.port), 128)
        serverSocket = ss
        ProxyState.resetTraffic()
        Thread({
            while (!stopped) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                ProxyState.sampleTraffic()
            }
        }, "tgws-sampler").apply { isDaemon = true; start() }
        Thread({ acceptLoop(ss) }, "tgws-accept").apply { isDaemon = true; start() }

        if (cfg.cfProxy && cfg.cfProxyDomains.isEmpty()) {
            executor.execute { refreshCfDomainsLoop() }
        }
        ProxyState.log(S.get(R.string.log_listen, cfg.host, cfg.port))
    }

    fun stop() {
        stopped = true
        try { serverSocket?.close() } catch (_: Exception) {}
        tracked.forEach { try { it.close() } catch (_: Exception) {} }
        tracked.clear()
        try { executor.shutdownNow() } catch (_: Exception) {}
        com.tgwsproxy.stats.DailyStats.flush()
        ProxyState.log(S.get(R.string.log_stopped))
    }

    /** Как start_cfproxy_domain_refresh(): сначала встроенный список, затем обновление с GitHub раз в час. */
    private fun refreshCfDomainsLoop() {
        try {
            while (!stopped) {
                val fetched = CfDomains.fetch()
                if (fetched.isNotEmpty()) {
                    cfDomains = fetched
                    ProxyState.log(S.get(R.string.log_cf_updated, fetched.size))
                } else {
                    ProxyState.log(S.get(R.string.log_cf_update_failed, cfDomains.size))
                }
                Thread.sleep(3_600_000L)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!stopped) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (stopped) break
                Thread.sleep(200)
                continue
            }
            try {
                executor.execute {
                    try { handle(client) } catch (_: Throwable) {}
                }
            } catch (_: Exception) {
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    private fun handle(client: Socket) {
        val addr = client.inetAddress
        val ip = addr?.hostAddress ?: "?"
        if (addr == null || !cfg.isAllowed(addr) || ClientRegistry.isBlocked(ip)) {
            ProxyState.log(S.get(R.string.log_rejected_ip, ip))
            closeQuietly(client)
            return
        }
        ClientRegistry.open(ip)
        ProxyState.updateStats { it.copy(active = it.active + 1, total = it.total + 1) }
        var upstream: Upstream? = null
        try {
            tracked += client
            client.tcpNoDelay = true
            client.keepAlive = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val rawIn = client.getInputStream()
            val rawOut = client.getOutputStream()
            var cin: InputStream = rawIn
            var cout: OutputStream = rawOut

            // Fake TLS: ClientHello → ServerHello, дальше всё внутри записей ApplicationData
            val fakeDomain = cfg.fakeTlsDomain
            if (fakeDomain.isNotEmpty()) {
                val first = rawIn.read()
                if (first < 0) return
                if (first != FakeTls.RECORD_HANDSHAKE) {
                    rawOut.write(FakeTls.redirectResponse(fakeDomain))
                    rawOut.flush()
                    return
                }
                val rest = ByteArray(4)
                readFully(rawIn, rest)
                val recLen = ((rest[2].toInt() and 0xff) shl 8) or (rest[3].toInt() and 0xff)
                if (recLen > 16384 + 256) {
                    ProxyState.updateStats { it.copy(failed = it.failed + 1) }; com.tgwsproxy.stats.DailyStats.addFailed()
                    return
                }
                val body = ByteArray(recLen)
                readFully(rawIn, body)
                val hello = ByteArray(5 + recLen)
                hello[0] = first.toByte()
                System.arraycopy(rest, 0, hello, 1, 4)
                System.arraycopy(body, 0, hello, 5, recLen)

                val verified = FakeTls.verifyClientHello(hello, cfg.secret)
                if (verified == null) {
                    ProxyState.log(S.get(R.string.log_fake_tls_masking, fakeDomain))
                    maskTo(client, hello, fakeDomain)
                    return
                }
                val serverHello = FakeTls.buildServerHello(cfg.secret, verified, FakeTls.parseClientHello(hello))
                Thread.sleep(8L + (Math.random() * 16).toLong())
                rawOut.write(serverHello)
                rawOut.flush()
                cin = FakeTls.TlsInput(rawIn)
                cout = FakeTls.TlsOutput(rawOut)
            }

            val init = ByteArray(64)
            readFully(cin, init)
            val hs = Obfuscated2.parseClientInit(init, cfg.secret)
            if (hs == null) {
                val looksLikeTls = (init[0].toInt() and 0xff) == FakeTls.RECORD_HANDSHAKE
                ProxyState.log(
                    when {
                        looksLikeTls && fakeDomain.isEmpty() ->
                            S.get(R.string.log_bad_key_ee)
                        fakeDomain.isNotEmpty() ->
                            S.get(R.string.log_bad_key_tls)
                        else ->
                            S.get(R.string.log_bad_key)
                    },
                )
                ProxyState.updateStats { it.copy(rejected = it.rejected + 1) }
                // Как в оригинале: не закрываем сразу, а читаем и выбрасываем до 64 КБ в течение 10 с,
                // чтобы клиент со старым ключом не уходил в цикл мгновенных переподключений.
                try {
                    client.soTimeout = 10_000
                    var remaining = 64 * 1024
                    val deadline = System.currentTimeMillis() + 10_000
                    val tmp = ByteArray(4096)
                    while (remaining > 0 && System.currentTimeMillis() < deadline) {
                        val n = cin.read(tmp, 0, minOf(tmp.size, remaining))
                        if (n < 0) break
                        remaining -= n
                    }
                } catch (_: Exception) {
                }
                return
            }
            client.soTimeout = 0

            // Тестовые DC приходят как 10001+ (см. _handle_client в оригинале)
            var dc = hs.dc
            val isTest = dc >= 10000
            if (isTest) dc -= 10000
            val relayDc = if (hs.isMedia) -dc else dc
            val tag = "DC$dc${if (isTest) "t" else ""}${if (hs.isMedia) "m" else ""}"

            upstream = connectUpstream(dc, hs.isMedia, isTest, hs.proto, tag)
            if (upstream == null) {
                ProxyState.log(S.get(R.string.log_no_route, tag))
                ProxyState.updateStats { it.copy(failed = it.failed + 1) }; com.tgwsproxy.stats.DailyStats.addFailed()
                return
            }
            val route: Upstream = upstream
            tracked += route
            ProxyState.updateStats {
                when (route.kind) {
                    RouteKind.WS -> it.copy(ws = it.ws + 1)
                    RouteKind.CF -> it.copy(cf = it.cf + 1)
                    RouteKind.WORKER -> it.copy(worker = it.worker + 1)
                    RouteKind.TCP -> it.copy(tcp = it.tcp + 1)
                }
            }
            com.tgwsproxy.stats.DailyStats.addRoute(route.kind)

            val relay = if (route.plainMode) null else Obfuscated2.buildRelayInit(hs.proto, relayDc)
            if (relay != null) route.send(relay.init)

            val downThread = Thread({
                try {
                    while (true) {
                        val data = route.receive() ?: break
                        val out = if (relay != null) hs.enc.process(relay.fromDc.process(data)) else hs.enc.process(data)
                        cout.write(out)
                        cout.flush()
                        ProxyState.addDown(out.size.toLong())
                        ClientRegistry.addDown(ip, out.size.toLong())
                    }
                } catch (_: Exception) {
                } finally {
                    closeQuietly(route)
                    closeQuietly(client)
                }
            }, "tgws-down").apply { isDaemon = true; start() }

            val splitter = if (route.splitPackets) MsgSplitter(hs.proto) else null
            val buf = ByteArray(32 * 1024)
            try {
                while (true) {
                    val n = cin.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    val plain = hs.dec.process(buf, 0, n)
                    if (relay == null) {
                        route.send(plain)
                    } else if (splitter != null) {
                        for (pkt in splitter.feed(plain, relay.toDc)) route.send(pkt)
                    } else {
                        route.send(relay.toDc.process(plain))
                    }
                    ProxyState.addUp(n.toLong())
                    ClientRegistry.addUp(ip, n.toLong())
                }
            } catch (_: Exception) {
            } finally {
                closeQuietly(route)
                closeQuietly(client)
            }
            downThread.join(2000)
        } catch (_: Exception) {
            // обрыв соединения — штатная ситуация
        } finally {
            tracked -= client
            upstream?.let { tracked -= it; closeQuietly(it) }
            closeQuietly(client)
            ClientRegistry.close(ip)
            ProxyState.updateStats {
                it.copy(active = (it.active - 1).coerceAtLeast(0))
            }
        }
    }

    /** Не-клиентские подключения (проверки, сканеры) отправляем на настоящий сайт-маску. */
    private fun maskTo(client: Socket, initial: ByteArray, domain: String) {
        val up = Socket()
        try {
            up.connect(InetSocketAddress(domain, 443), 10_000)
            up.tcpNoDelay = true
            tracked += up
            up.getOutputStream().apply { write(initial); flush() }
            client.soTimeout = 0
            Thread({
                try { copyStream(up.getInputStream(), client.getOutputStream()) } catch (_: Exception) {}
                finally { closeQuietly(up); closeQuietly(client) }
            }, "tgws-mask").apply { isDaemon = true; start() }
            try { copyStream(client.getInputStream(), up.getOutputStream()) } catch (_: Exception) {}
        } catch (e: Exception) {
            ProxyState.log(S.get(R.string.log_mask_failed, domain, e.message ?: e.javaClass.simpleName))
        } finally {
            tracked -= up
            closeQuietly(up)
        }
    }

    private fun copyStream(input: InputStream, output: OutputStream) {
        val b = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(b)
            if (n < 0) break
            output.write(b, 0, n)
            output.flush()
        }
    }

    /** Закрывает все соединения с указанного адреса (кнопка «Отключить»). */
    fun disconnectClient(ip: String) {
        var n = 0
        for (c in tracked.toList()) {
            if (c is Socket && c.inetAddress?.hostAddress == ip) {
                closeQuietly(c)
                n++
            }
        }
        ProxyState.log(S.get(R.string.log_disconnected, ip, n))
    }

    // ───────────────────────── маршрутизация ─────────────────────────

    private fun connectUpstream(dc: Int, media: Boolean, test: Boolean, proto: Proto, tag: String): Upstream? {
        val fallbackDst = (if (test) DC_TEST_IPS else DC_DEFAULT_IPS)[dc]

        // 1. Прямой WebSocket (аналог _WsPool._connect_one, но по требованию)
        val ip = cfg.dcIp[dc]
        if (ip != null) directWs(ip, dc, media, test, tag)?.let { return it }

        // 2. Cloudflare Worker (в оригинале пробуется раньше CF-прокси)
        if (cfg.cfWorker && fallbackDst != null) {
            for (worker in cfg.cfWorkerDomains.shuffled()) {
                ProxyState.log(S.get(R.string.log_try_worker, tag, worker))
                when (val r = WsConnector.connect(worker, worker, "/apiws?dst=$fallbackDst&dc=$dc", FALLBACK_TIMEOUT_MS)) {
                    is WsResult.Ok -> {
                        ProxyState.log("$tag → Worker ($worker)")
                        return WsUpstream(r.ws, RouteKind.WORKER, false) // для Worker splitter не используется
                    }
                    is WsResult.Redirect -> ProxyState.log("$tag: Worker redirect ${r.code}")
                    is WsResult.Fail -> ProxyState.log("$tag: Worker $worker — ${r.reason}")
                }
            }
        }

        // 3. CF-прокси: kwsN.<домен> (домен по DC «залипает» на последнем рабочем)
        if (cfg.cfProxy && !test) {
            val candidates = cfCandidates(dc)
            // Медиа через CF по HTTP/2 (экспериментально); при неудаче — обычный WebSocket через CF
            if (media && cfg.cfH2 && DC_DEFAULT_IPS.containsKey(dc)) {
                h2Pool.open(dc, candidates, proto, tag)?.let {
                    ProxyState.log("$tag → CF HTTP/2 (${it.host})")
                    return it
                }
            }
            for (base in candidates) {
                val domain = "kws$dc.$base"
                when (val r = WsConnector.connect(domain, domain, "/apiws", FALLBACK_TIMEOUT_MS)) {
                    is WsResult.Ok -> {
                        cfSticky[dc] = base
                        ProxyState.log("$tag → CF ($domain)")
                        return WsUpstream(r.ws, RouteKind.CF, true)
                    }
                    is WsResult.Redirect -> ProxyState.log("$tag: CF redirect ${r.code} ($domain)")
                    is WsResult.Fail -> ProxyState.log("$tag: CF $domain — ${r.reason}")
                }
            }
        }

        // 4. Прямой TCP на адрес DC, с экспоненциальной паузой после неудач (30 с … 1 ч)
        if (fallbackDst != null) tcpFallback(fallbackDst, tag)?.let { return it }
        return null
    }

    private fun directWs(ip: String, dc: Int, media: Boolean, test: Boolean, tag: String): Upstream? {
        val key = "$dc|$media|$test"
        val now = System.currentTimeMillis()
        if ((wsRetryAfter[key] ?: 0L) > now) return null

        val wsDc = if (dc == 203) 2 else dc
        val domains = if (media) listOf("kws$wsDc-1.web.telegram.org", "kws$wsDc.web.telegram.org")
        else listOf("kws$wsDc.web.telegram.org")
        val path = if (test) "/apiws_test" else "/apiws"

        for (domain in domains) {
            val modes = if (frontingFirst) listOf(true, false) else listOf(false, true)
            for (fronted in modes) {
                val sni = if (fronted) FRONTING_SNI else null
                when (val r = WsConnector.connect(ip, domain, path, if (fronted) 7_000 else 8_000, sni)) {
                    is WsResult.Ok -> {
                        frontingFirst = fronted
                        wsFailures.remove(key)
                        wsRetryAfter.remove(key)
                        ProxyState.log("$tag → WS ($domain${if (fronted) ", fronting" else ""})")
                        return WsUpstream(r.ws, RouteKind.WS, true)
                    }
                    is WsResult.Redirect -> ProxyState.log("$tag: WS redirect ${r.code} ($domain)")
                    is WsResult.Fail -> ProxyState.log("$tag: WS ${if (fronted) "(fronting) " else ""}— ${r.reason}")
                }
            }
        }
        val failures = (wsFailures[key] ?: 0) + 1
        wsFailures[key] = failures
        val delaySec = min(1L shl min(failures - 1, 12), 3600L)
        wsRetryAfter[key] = now + delaySec * 1000
        ProxyState.log(S.get(R.string.log_ws_unavailable, tag, delaySec))
        return null
    }

    private fun cfCandidates(dc: Int): List<String> {
        val domains = cfg.cfProxyDomains.ifEmpty { cfDomains }
        val current = cfSticky[dc]?.takeIf { it in domains }
        val rest = domains.filter { it != current }.shuffled()
        return (listOfNotNull(current) + rest).take(MAX_CF_DOMAINS)
    }

    private fun tcpFallback(dst: String, tag: String): Upstream? {
        val now = System.currentTimeMillis()
        if ((tcpRetryAfter[dst] ?: 0L) > now) {
            ProxyState.log(S.get(R.string.log_tcp_skipped, tag, dst))
            return null
        }
        val s = Socket()
        return try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(dst, 443), TCP_TIMEOUT_MS)
            s.keepAlive = true
            tcpFailures.remove(dst)
            tcpRetryAfter.remove(dst)
            ProxyState.log("$tag → TCP ($dst)")
            TcpUpstream(s)
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            val failures = (tcpFailures[dst] ?: 0) + 1
            tcpFailures[dst] = failures
            val delaySec = min(30L * (1L shl min(failures - 1, 7)), 3600L)
            tcpRetryAfter[dst] = now + delaySec * 1000
            ProxyState.log(S.get(R.string.log_tcp_failed, tag, dst, e.message ?: e.javaClass.simpleName, delaySec))
            null
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun closeQuietly(c: Closeable) {
        try { c.close() } catch (_: Exception) {}
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000
        const val FALLBACK_TIMEOUT_MS = 8_000
        const val TCP_TIMEOUT_MS = 8_000
        const val MAX_CF_DOMAINS = 5
        const val FRONTING_SNI = "sprinthost.ru"

        /** DC_DEFAULT_IPS из proxy/utils.py */
        val DC_DEFAULT_IPS = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "149.154.171.5",
            203 to "91.105.192.100",
        )

        /** DC_TEST_IPS из proxy/utils.py */
        val DC_TEST_IPS = mapOf(
            1 to "149.154.175.10",
            2 to "149.154.167.40",
            3 to "149.154.175.117",
        )
    }
}
