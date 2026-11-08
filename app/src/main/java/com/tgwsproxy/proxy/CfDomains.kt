package com.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Домены CF-прокси по умолчанию (обфусцированы так же, как в proxy/config.py оригинала). */
object CfDomains {
    private const val HOST = "raw.githubusercontent.com"
    private const val PINNED_IP = "185.199.109.133" // как _GITHUB_IPS в utils.py
    private const val PATH = "/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
    const val MIN_VALID = 3

    private val ENCODED = listOf(
        "virkgj.com", "vmmzovy.com", "mkuosckvso.com", "zaewayzmplad.com", "twdmbzcm.com",
        "awzwsldi.com", "clngqrflngqin.com", "tjacxbqtj.com", "bxaxtxmrw.com", "dmohrsgmohcrwb.com",
        "vwbmtmoi.com", "khgrre.com", "ulihssf.com", "tmhqsdqmfpmk.com", "xwuwoqbm.com",
        "orgcnunpj.com", "zhkuldz.com", "zypoljnslxa.com", "efabnxaowuzs.com", "zaftuzsftqdq.com",
    )

    private val DOMAIN = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")

    /** Как _dd(): сдвиг букв назад на n (число букв в префиксе), суффикс .com → .co.uk. */
    fun deobfuscate(s: String): String {
        if (!s.endsWith(".com")) return s
        val prefix = s.removeSuffix(".com")
        val n = prefix.count { it in 'a'..'z' || it in 'A'..'Z' }
        val sb = StringBuilder()
        for (c in prefix) {
            sb.append(
                when (c) {
                    in 'a'..'z' -> 'a' + Math.floorMod(c - 'a' - n, 26)
                    in 'A'..'Z' -> 'A' + Math.floorMod(c - 'A' - n, 26)
                    else -> c
                },
            )
        }
        return sb.toString() + ".co.uk"
    }

    fun fallback(): List<String> = ENCODED.map { deobfuscate(it) }

    /** Список с GitHub; пустой, если не удалось или данных слишком мало (как refresh_cfproxy_domains). */
    fun fetch(): List<String> {
        val text = httpsGetPinned() ?: httpsGetPlain() ?: return emptyList()
        val domains = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { deobfuscate(it).lowercase() }
            .filter { DOMAIN.matches(it) }
            .distinct()
            .toList()
        return if (domains.size >= MIN_VALID) domains else emptyList()
    }

    private fun httpsGetPlain(): String? = try {
        val conn = URL("https://$HOST$PATH?${System.nanoTime()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "tg-ws-proxy")
        conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    } catch (_: Exception) {
        null
    }

    /** Прямое подключение к закреплённому IP (если DNS до GitHub испорчен). */
    private fun httpsGetPinned(): String? {
        val raw = Socket()
        return try {
            raw.connect(InetSocketAddress(PINNED_IP, 443), 8000)
            raw.soTimeout = 8000
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, HOST, 443, true) as SSLSocket
            val p = ssl.sslParameters
            p.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = p
            ssl.startHandshake()
            val req = "GET $PATH?${System.nanoTime()} HTTP/1.1\r\nHost: $HOST\r\nUser-Agent: tg-ws-proxy\r\nConnection: close\r\n\r\n"
            ssl.outputStream.write(req.toByteArray(Charsets.US_ASCII))
            ssl.outputStream.flush()
            val all = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = ssl.inputStream.read(buf)
                if (n < 0) break
                all.write(buf, 0, n)
                if (all.size() > 256 * 1024) break
            }
            val bytes = all.toByteArray()
            val text = String(bytes, Charsets.ISO_8859_1)
            val split = text.indexOf("\r\n\r\n")
            if (split < 0 || !text.startsWith("HTTP/1.1 200")) return null
            val head = text.substring(0, split).lowercase()
            var body = bytes.copyOfRange(split + 4, bytes.size)
            if (head.contains("transfer-encoding: chunked")) body = dechunk(body)
            String(body, Charsets.UTF_8)
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            try { raw.close() } catch (_: IOException) {}
        }
    }

    private fun dechunk(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < data.size) {
            var e = i
            while (e + 1 < data.size && !(data[e] == '\r'.code.toByte() && data[e + 1] == '\n'.code.toByte())) e++
            val size = String(data, i, e - i, Charsets.US_ASCII).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = e + 2
            if (start + size > data.size) { out.write(data, start, data.size - start); break }
            out.write(data, start, size)
            i = start + size + 2
        }
        return out.toByteArray()
    }
}
