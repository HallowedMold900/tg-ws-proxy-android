package com.tgwsproxy.util

import com.tgwsproxy.R
import com.tgwsproxy.S

object Fmt {
    fun bytes(b: Long): String = when {
        b < 1024 -> S.get(R.string.unit_b, b)
        b < 1024 * 1024 -> S.get(R.string.unit_kb, "%.1f".format(b / 1024.0))
        b < 1024L * 1024 * 1024 -> S.get(R.string.unit_mb, "%.1f".format(b / 1048576.0))
        else -> S.get(R.string.unit_gb, "%.2f".format(b / 1073741824.0))
    }

    fun speed(bytesPerSec: Long): String = bytes(bytesPerSec) + S.get(R.string.unit_per_sec)
}
