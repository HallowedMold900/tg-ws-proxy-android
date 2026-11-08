package com.tgwsproxy.proxy

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** AES-256-CTR с непрерывным состоянием потока. Один экземпляр — один поток (thread). */
class AesCtr(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").also {
        it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun process(data: ByteArray, off: Int = 0, len: Int = data.size - off): ByteArray {
        val out = cipher.update(data, off, len)
        check(out != null && out.size == len) { "CTR: unexpected output size" }
        return out
    }
}

enum class Proto(val tag: ByteArray) {
    ABRIDGED(ByteArray(4) { 0xef.toByte() }),
    INTERMEDIATE(ByteArray(4) { 0xee.toByte() }),
    PADDED(ByteArray(4) { 0xdd.toByte() });

    companion object {
        fun fromTag(b: ByteArray): Proto? = entries.firstOrNull { it.tag.contentEquals(b) }
    }
}

class ClientHandshake(val proto: Proto, val dcRaw: Int, val dec: AesCtr, val enc: AesCtr) {
    val dc: Int get() = abs(dcRaw)
    val isMedia: Boolean get() = dcRaw < 0
}

class RelayInit(val init: ByteArray, val toDc: AesCtr, val fromDc: AesCtr)

object Obfuscated2 {
    private val rnd = SecureRandom()

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    /** Разбор 64-байтного init-пакета клиента Telegram. null — неверный ключ / не MTProto. */
    fun parseClientInit(init: ByteArray, secret: ByteArray): ClientHandshake? {
        val prekeyIv = init.copyOfRange(8, 56)
        val dec = AesCtr(
            sha256(prekeyIv.copyOfRange(0, 32), secret),
            prekeyIv.copyOfRange(32, 48),
        )
        val plain = dec.process(init)
        val proto = Proto.fromTag(plain.copyOfRange(56, 60)) ?: return null
        val dcRaw = ((plain[60].toInt() and 0xff) or (plain[61].toInt() shl 8)).toShort().toInt()

        val rev = prekeyIv.reversedArray()
        val enc = AesCtr(
            sha256(rev.copyOfRange(0, 32), secret),
            rev.copyOfRange(32, 48),
        )
        return ClientHandshake(proto, dcRaw, dec, enc)
    }

    /** Новый init-пакет для соединения прокси → Telegram DC (без секрета). */
    fun buildRelayInit(proto: Proto, dcRaw: Int): RelayInit {
        val init = ByteArray(64)
        while (true) {
            rnd.nextBytes(init)
            if (init[0] == 0xef.toByte()) continue
            val first = le32(init, 0)
            if (first == 0x44414548 || first == 0x54534f50 || first == 0x20544547 ||
                first == 0x4954504f || first == 0xdddddddd.toInt() ||
                first == 0xeeeeeeee.toInt() || first == 0x02010316
            ) continue
            if (le32(init, 4) == 0) continue
            break
        }
        proto.tag.copyInto(init, 56)
        init[60] = dcRaw.toByte()
        init[61] = (dcRaw shr 8).toByte()

        val toDc = AesCtr(init.copyOfRange(8, 40), init.copyOfRange(40, 56))
        val encrypted = toDc.process(init)           // состояние шифра уходит на 64 байта вперёд
        System.arraycopy(encrypted, 56, init, 56, 8) // в пакет попадают зашифрованные байты 56..63

        val rev = init.copyOfRange(8, 56).reversedArray()
        val fromDc = AesCtr(rev.copyOfRange(0, 32), rev.copyOfRange(32, 48))
        return RelayInit(init, toDc, fromDc)
    }
}
