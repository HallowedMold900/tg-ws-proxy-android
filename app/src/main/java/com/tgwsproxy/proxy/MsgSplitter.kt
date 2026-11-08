package com.tgwsproxy.proxy

/**
 * Каждое WebSocket-сообщение к Telegram должно содержать ровно один транспортный пакет MTProto.
 * Разбирает расшифрованный поток клиента на пакеты и шифрует каждый в порядке потока.
 */
class MsgSplitter(private val proto: Proto) {
    private var buf = ByteArray(64 * 1024)
    private var size = 0
    private var disabled = false

    fun feed(plain: ByteArray, enc: AesCtr): List<ByteArray> {
        if (plain.isEmpty()) return emptyList()
        if (disabled) return listOf(enc.process(plain))

        if (size + plain.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + plain.size))
        System.arraycopy(plain, 0, buf, size, plain.size)
        size += plain.size

        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < size) {
            val total = packetLength(pos)
            if (total == NEED_MORE) break
            if (total == DISABLE) {
                disabled = true
                out += enc.process(buf, pos, size - pos)
                pos = size
                break
            }
            if (size - pos < total) break
            out += enc.process(buf, pos, total)
            pos += total
        }
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, size - pos)
            size -= pos
        }
        return out
    }

    /** Полная длина пакета (с заголовком), NEED_MORE или DISABLE (странные данные — не режем). */
    private fun packetLength(pos: Int): Int {
        val avail = size - pos
        when (proto) {
            Proto.ABRIDGED -> {
                val b0 = buf[pos].toInt() and 0xff
                val extended = b0 == 0x7f || b0 == 0xff
                val hdr = if (extended) 4 else 1
                if (avail < hdr) return NEED_MORE
                val words = if (extended) {
                    (buf[pos + 1].toInt() and 0xff) or ((buf[pos + 2].toInt() and 0xff) shl 8) or
                        ((buf[pos + 3].toInt() and 0xff) shl 16)
                } else {
                    b0 and 0x7f
                }
                val payload = words * 4
                if (payload == 0 || payload > MAX_PACKET) return DISABLE
                return hdr + payload
            }
            Proto.INTERMEDIATE, Proto.PADDED -> {
                if (avail < 4) return NEED_MORE
                val len = ((buf[pos].toInt() and 0xff) or ((buf[pos + 1].toInt() and 0xff) shl 8) or
                    ((buf[pos + 2].toInt() and 0xff) shl 16) or ((buf[pos + 3].toInt() and 0xff) shl 24)) and 0x7fffffff
                if (len == 0 || len > MAX_PACKET) return DISABLE
                return 4 + len
            }
        }
    }

    private companion object {
        const val NEED_MORE = -1
        const val DISABLE = -2
        const val MAX_PACKET = 16 * 1024 * 1024
    }
}
