package com.vditunnel.bridge

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

/** Renders raw frame bytes to a QR BufferedImage in BYTE mode (Latin-1 = 1 char/byte). */
object QrRenderer {
    // ECC M (15%): the screen->capture path is clean, and the LT fountain + CRC already
    // recover dropped/garbled frames, so we spend QR capacity on payload instead of on Q.
    fun render(frame: ByteArray, sizePx: Int, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.M): BufferedImage {
        val text = String(frame, Charsets.ISO_8859_1)
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ecc,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val img = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until sizePx) for (x in 0 until sizePx)
            img.setRGB(x, y, if (matrix[x, y]) 0x000000 else 0xFFFFFF)
        return img
    }
}
