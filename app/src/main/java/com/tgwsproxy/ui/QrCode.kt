package com.tgwsproxy.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR-код на белой подложке (читается и в тёмной теме). Размер задаётся через modifier. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix? = remember(text) {
        try {
            QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 1, 1,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        } catch (_: Exception) {
            null
        }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val m = matrix ?: return@Canvas
            val cell = size.width / m.width
            for (y in 0 until m.height) {
                for (x in 0 until m.width) {
                    if (m.get(x, y)) {
                        drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}
