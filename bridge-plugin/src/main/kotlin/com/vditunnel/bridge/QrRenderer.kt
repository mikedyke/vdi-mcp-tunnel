package com.vditunnel.bridge

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage

/** Renders raw frame bytes to a QR BufferedImage in BYTE mode (Latin-1 = 1 char/byte). */
object QrRenderer {
    // ECC L (7%), raised from M (15%) on 2026-09-11 to buy payload.
    //
    // Measured with this ZXing (3.5.3, ISO-8859-1 hint, margin 2), same physical QR size --
    // V18, 89 modules + 2-module quiet zone each side = 93-module matrix:
    //     ECC M -> 536 byte frame (512B symbol)
    //     ECC L -> 716 byte frame (692B symbol)   = +35% throughput, identical geometry
    // At ECC L, 717 bytes is the last frame that stays at 93; 718 spills to V19 (97).
    //
    // Spending capacity on payload rather than redundancy is right here because the QR is a
    // crisp *rendered* image, not a photograph, and because a corrupted frame is already
    // cheap: it fails CRC, is discarded, and the LT fountain simply consumes the next symbol.
    // Error correction inside the QR duplicates recovery the transport already provides.
    fun render(frame: ByteArray, sizePx: Int, ecc: ErrorCorrectionLevel = ErrorCorrectionLevel.L): BufferedImage {
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
