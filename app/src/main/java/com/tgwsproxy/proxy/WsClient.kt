package com.tgwsproxy.proxy

import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

sealed interface WsResult {
    class Ok(val ws: WsConn) : WsResult
    class Redirect(val code: Int) : WsResult
    class Fail(val reason: String, val connectTimeout: Boolean = false) : WsResult
}

/** Минимальный клиент WebSocket (RFC 6455) поверх уже установленного TLS-сокета. */
class WsConn(private val sock: Socket) : Closeable {
    private val input = BufferedInputStream(sock.getInputStream(), 32 * 1024)
    private val output = BufferedOutputStream(sock.getOutputStream(), 32 * 1024)
    private val rnd = SecureRandom()
    private val writeLock = Any()

    /** Отправляет HTTP Upgrade и возвращает код ответа. */
    fun handshake(host: String, path: String): Int {
        val keyBytes = ByteArray(16).also { rnd.nextBytes(it) }
        val sb = StringBuilder()
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n")
        sb.append("Host: ").append(host).append("\r\n")
        sb.append("Upgrade: websocket\r\n")
        sb.append("Connection: Upgrade\r\n")
        sb.append("Sec-WebSocket-Key: ").append(Base64.encodeToString(keyBytes, Base64.NO_WRAP)).append("\r\n")
        sb.append("Sec-WebSocket-Version: 13\r\n")
        sb.append("Sec-WebSocket-Protocol: binary\r\n\r\n")
        synchronized(writeLock) {
            output.write(sb.toString().toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw EOFException("соединение закрыто при рукопожатии")
            head.append(b.toChar())
            if (head.length > 16 * 1024) throw IOException("слишком длинный ответ")
        }
        val status = head.lineSequence().first().split(" ")
        return status.getOrNull(1)?.toIntOrNull() ?: throw IOException("плохой ответ сервера")
    }

    fun sendBinary(data: ByteArray) = sendFrame(0x2, data)

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        synchronized(writeLock) {
            val len = payload.size
            output.write(0x80 or opcode)
            when {
                len < 126 -> output.write(0x80 or len)
                len <= 0xFFFF -> {
                    output.write(0x80 or 126)
                    output.write(len shr 8)
                    output.write(len and 0xff)
                }
                else -> {
                    output.write(0x80 or 127)
                    for (i in 7 downTo 0) output.write(((len.toLong() shr (8 * i)) and 0xff).toInt())
                }
            }
            val mask = ByteArray(4).also { rnd.nextBytes(it) }
            output.write(mask)
            val masked = ByteArray(len) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
            output.write(masked)
            output.flush()
        }
    }

    /** Следующее сообщение или null, если соединение закрыто. */
    fun receive(): ByteArray? {
        var acc: ByteArrayOutputStream? = null
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = readByte()
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0f
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) {
                len = ((readByte() shl 8) or readByte()).toLong()
            } else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or readByte().toLong() }
            }
            if (len > MAX_FRAME) throw IOException("слишком большой кадр")
            val mask = if ((b1 and 0x80) != 0) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(len.toInt())
            readFully(payload)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

            when (opcode) {
                0x8 -> {
                    try { sendFrame(0x8, payload) } catch (_: IOException) {}
                    return null
                }
                0x9 -> sendFrame(0xA, payload)
                0xA -> Unit
                0x1, 0x2 -> {
                    if (fin) return payload
                    acc = ByteArrayOutputStream().also { it.write(payload) }
                }
                0x0 -> {
                    val a = acc ?: throw IOException("неожиданное продолжение кадра")
                    a.write(payload)
                    if (fin) return a.toByteArray()
                }
                else -> throw IOException("неизвестный opcode $opcode")
            }
        }
    }

    private fun readByte(): Int {
        val v = input.read()
        if (v < 0) throw EOFException()
        return v
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    override fun close() {
        try { sock.close() } catch (_: Exception) {}
    }

    private companion object {
        const val MAX_FRAME = 8L * 1024 * 1024
    }
}

object WsConnector {
    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /**
     * TCP на [tcpHost]:443, TLS, затем WebSocket Upgrade на wss://[host][path].
     * Обычно SNI = [host]. Если задан [sni] — domain fronting: в TLS уходит чужой SNI,
     * сертификат по имени не проверяется (цепочка доверия проверяется), а Host остаётся [host].
     * Для прямого доступа к Telegram tcpHost — IP из «DC → IP», host — kwsN.web.telegram.org.
     * Для CF-прокси и Worker tcpHost = host = домен.
     */
    fun connect(tcpHost: String, host: String, path: String, timeoutMs: Int, sni: String? = null): WsResult {
        val raw = Socket()
        try {
            raw.tcpNoDelay = true
            try {
                raw.connect(InetSocketAddress(tcpHost, 443), timeoutMs)
            } catch (e: SocketTimeoutException) {
                raw.close()
                return WsResult.Fail("TCP: таймаут", connectTimeout = true)
            } catch (e: IOException) {
                raw.close()
                return WsResult.Fail("TCP: ${e.message ?: e.javaClass.simpleName}")
            }
            raw.soTimeout = timeoutMs
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, sni ?: host, 443, true) as SSLSocket
            if (sni == null) {
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
            }
            ssl.startHandshake()

            val ws = WsConn(ssl)
            val code = ws.handshake(host, path)
            if (code == 101) {
                ssl.soTimeout = 0
                ssl.keepAlive = true
                return WsResult.Ok(ws)
            }
            ws.close()
            return if (code in REDIRECTS) WsResult.Redirect(code) else WsResult.Fail("HTTP $code")
        } catch (e: SocketTimeoutException) {
            try { raw.close() } catch (_: IOException) {}
            return WsResult.Fail("таймаут TLS/WebSocket")
        } catch (e: Exception) {
            try { raw.close() } catch (_: IOException) {}
            return WsResult.Fail(e.message ?: e.javaClass.simpleName)
        }
    }
}
