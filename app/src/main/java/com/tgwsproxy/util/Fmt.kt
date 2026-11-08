package com.tgwsproxy.util

object Fmt {
    fun bytes(b: Long): String = when {
        b < 1024 -> "$b Б"
        b < 1024 * 1024 -> "%.1f КБ".format(b / 1024.0)
        b < 1024L * 1024 * 1024 -> "%.1f МБ".format(b / 1048576.0)
        else -> "%.2f ГБ".format(b / 1073741824.0)
    }

    fun speed(bytesPerSec: Long): String = bytes(bytesPerSec) + "/с"
}
