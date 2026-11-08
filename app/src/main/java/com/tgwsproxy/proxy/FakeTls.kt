package com.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Fake TLS (ee-секрет) по образцу proxy/fake_tls.py:
 * клиент присылает ClientHello с HMAC в поле random, прокси отвечает ServerHello + CCS + ApplicationData,
 * дальше obfuscated2 идёт внутри TLS-записей ApplicationData.
 */
object FakeTls {
    const val RECORD_HANDSHAKE = 0x16
    private const val RECORD_CCS = 0x14
    private const val RECORD_APPDATA = 0x17
    private const val APPDATA_MAX = 16384
    private const val CLIENT_RANDOM_OFFSET = 11
    private const val TIMESTAMP_TOLERANCE = 120L
    private const val EXT_SUPPORTED_VERSIONS = 0x002b
    private const val EXT_KEY_SHARE = 0x0033
    private const val GROUP_X25519 = 0x001d
    private const val GROUP_X25519_MLKEM768 = 0x11ec
    private const val MLKEM768_CLIENT_SHARE_LEN = 1216
    private const val MLKEM768_CIPHERTEXT_LEN = 1088
    private val CIPHER_PREFERENCE = intArrayOf(0x1301, 0x1303, 0x1302)
    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)
    private val rnd = SecureRandom()

    class ClientHelloInfo(val suites: Set<Int>, val keyShares: Map<Int, Int>)
    class Verified(val clientRandom: ByteArray, val sessionId: ByteArray)

    private val EMPTY = ClientHelloInfo(emptySet(), emptyMap())

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun u16(d: ByteArray, p: Int): Int = ((d[p].toInt() and 0xff) shl 8) or (d[p + 1].toInt() and 0xff)

    fun verifyClientHello(data: ByteArray, secret: ByteArray): Verified? {
        val n = data.size
        if (n < 43) return null
        if ((data[0].toInt() and 0xff) != RECORD_HANDSHAKE) return null
        if (data[5].toInt() != 0x01) return null

        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + 32)
        val zeroed = data.copyOf()
        java.util.Arrays.fill(zeroed, CLIENT_RANDOM_OFFSET, CLIENT_RANDOM_OFFSET + 32, 0)
        val expected = hmac(secret, zeroed)

        if (!MessageDigest.isEqual(expected.copyOfRange(0, 28), clientRandom.copyOfRange(0, 28))) return null

        var ts = 0L
        for (i in 0 until 4) {
            val b = (clientRandom[28 + i].toInt() xor expected[28 + i].toInt()) and 0xff
            ts = ts or (b.toLong() shl (8 * i))
        }
        val now = System.currentTimeMillis() / 1000
        if (abs(now - ts) > TIMESTAMP_TOLERANCE) return null

        var session = ByteArray(32)
        if (n >= 76 && data[43].toInt() == 0x20) session = data.copyOfRange(44, 76)
        return Verified(clientRandom, session)
    }

    fun parseClientHello(data: ByteArray): ClientHelloInfo {
        try {
            var pos = 5 + 4 + 2 + 32
            pos += 1 + (data[pos].toInt() and 0xff)

            val suitesLen = u16(data, pos)
            pos += 2
            val suitesEnd = pos + suitesLen
            if (suitesEnd > data.size) return EMPTY
            val suites = HashSet<Int>()
            var o = pos
            while (o + 1 < suitesEnd) {
                suites += u16(data, o)
                o += 2
            }
            pos = suitesEnd
            pos += 1 + (data[pos].toInt() and 0xff)

            val extTotal = u16(data, pos)
            pos += 2
            val extEnd = minOf(pos + extTotal, data.size)
            val shares = HashMap<Int, Int>()
            while (pos + 4 <= extEnd) {
                val type = u16(data, pos)
                val len = u16(data, pos + 2)
                pos += 4
                if (type == EXT_KEY_SHARE && pos + 2 <= extEnd) {
                    val sharesEnd = minOf(pos + 2 + u16(data, pos), extEnd)
                    var off = pos + 2
                    while (off + 4 <= sharesEnd) {
                        val group = u16(data, off)
                        val keyLen = u16(data, off + 2)
                        off += 4 + keyLen
                        if (off <= sharesEnd) shares.putIfAbsent(group, keyLen)
                    }
                }
                pos += len
            }
            return ClientHelloInfo(suites, shares)
        } catch (_: IndexOutOfBoundsException) {
            return EMPTY
        }
    }

    private fun be16(out: ByteArrayOutputStream, v: Int) {
        out.write(v shr 8)
        out.write(v and 0xff)
    }

    private fun x25519PublicKey(): ByteArray {
        val key = ByteArray(32)
        rnd.nextBytes(key)
        key[31] = (key[31].toInt() and 0x7f).toByte()
        return key
    }

    fun buildServerHello(secret: ByteArray, v: Verified, info: ClientHelloInfo): ByteArray {
        val group: Int
        val keyExchange: ByteArray
        if (info.keyShares[GROUP_X25519_MLKEM768] == MLKEM768_CLIENT_SHARE_LEN) {
            group = GROUP_X25519_MLKEM768
            keyExchange = ByteArray(MLKEM768_CIPHERTEXT_LEN).also { rnd.nextBytes(it) } + x25519PublicKey()
        } else {
            group = GROUP_X25519
            keyExchange = x25519PublicKey()
        }

        val ext = ByteArrayOutputStream()
        be16(ext, EXT_SUPPORTED_VERSIONS); be16(ext, 2); be16(ext, 0x0304)
        be16(ext, EXT_KEY_SHARE); be16(ext, 4 + keyExchange.size); be16(ext, group); be16(ext, keyExchange.size)
        ext.write(keyExchange)
        val extensions = ext.toByteArray()

        val suite = CIPHER_PREFERENCE.firstOrNull { it in info.suites } ?: CIPHER_PREFERENCE[0]
        val body = ByteArrayOutputStream()
        body.write(0x03); body.write(0x03)
        body.write(ByteArray(32))
        body.write(v.sessionId.size); body.write(v.sessionId)
        be16(body, suite)
        body.write(0x00)
        be16(body, extensions.size); body.write(extensions)
        val bodyBytes = body.toByteArray()

        val message = ByteArrayOutputStream()
        message.write(0x02)
        message.write(bodyBytes.size shr 16); message.write((bodyBytes.size shr 8) and 0xff); message.write(bodyBytes.size and 0xff)
        message.write(bodyBytes)
        val messageBytes = message.toByteArray()

        val response = ByteArrayOutputStream()
        response.write(RECORD_HANDSHAKE); response.write(0x03); response.write(0x03)
        be16(response, messageBytes.size)
        response.write(messageBytes)
        response.write(CCS_FRAME)

        val appLen = 64 + rnd.nextInt(97) // 64..160
        response.write(RECORD_APPDATA); response.write(0x03); response.write(0x03)
        be16(response, appLen)
        response.write(ByteArray(appLen).also { rnd.nextBytes(it) })

        val result = response.toByteArray()
        val serverRandom = hmac(secret, v.clientRandom + result)
        System.arraycopy(serverRandom, 0, result, CLIENT_RANDOM_OFFSET, 32)
        return result
    }

    fun redirectResponse(domain: String): ByteArray =
        ("HTTP/1.1 301 Moved Permanently\r\nLocation: https://$domain/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .toByteArray(Charsets.US_ASCII)

    /** Читает полезные данные из записей ApplicationData, пропуская CCS. */
    class TlsInput(private val inp: InputStream) : InputStream() {
        private var left = 0

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (left == 0) {
                val type = inp.read()
                if (type < 0) return -1
                val hdr = ByteArray(4)
                readFully(hdr)
                val recLen = ((hdr[2].toInt() and 0xff) shl 8) or (hdr[3].toInt() and 0xff)
                when (type) {
                    RECORD_CCS -> if (recLen > 0) readFully(ByteArray(recLen))
                    RECORD_APPDATA -> left = recLen
                    else -> return -1
                }
            }
            val n = inp.read(b, off, minOf(len, left))
            if (n < 0) return -1
            left -= n
            return n
        }

        private fun readFully(buf: ByteArray) {
            var o = 0
            while (o < buf.size) {
                val n = inp.read(buf, o, buf.size - o)
                if (n < 0) throw EOFException()
                o += n
            }
        }
    }

    /** Заворачивает данные в записи ApplicationData (до 16 КБ). */
    class TlsOutput(private val out: OutputStream) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            val records = (len + APPDATA_MAX - 1) / APPDATA_MAX
            val buf = ByteArray(len + 5 * records)
            var p = 0
            var o = off
            var remaining = len
            while (remaining > 0) {
                val c = minOf(remaining, APPDATA_MAX)
                buf[p++] = RECORD_APPDATA.toByte(); buf[p++] = 0x03; buf[p++] = 0x03
                buf[p++] = (c shr 8).toByte(); buf[p++] = (c and 0xff).toByte()
                System.arraycopy(b, o, buf, p, c)
                p += c; o += c; remaining -= c
            }
            out.write(buf)
        }

        override fun flush() = out.flush()
    }
}
