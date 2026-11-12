package com.progeo.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Frames as the camera delivers them: Y plane with row padding, in sensor orientation. */
class CodeDecoderTest {

    /** [matrix] centered on a gray frame, rows [stride] bytes apart. */
    private fun frame(matrix: BitMatrix, width: Int, height: Int, stride: Int, invert: Boolean = false): ByteArray {
        val bytes = ByteArray(stride * height) { 0x80.toByte() }
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            val dark = matrix[x, y] != invert
            bytes[(top + y) * stride + left + x] = if (dark) 0x10 else 0xF0.toByte()
        }
        return bytes
    }

    @Test
    fun `qr code with row padding`() {
        val text = "https://dashboard.progeo.com/login/link/Xk3v9J2mQp7sLr0aBcDeFgHiJkLmNoPqRsTuVwXyZ01"
        val qr = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300)
        assertEquals(text, CodeDecoder(CodeFormat.QR_ONLY).decode(frame(qr, 640, 480, 704), 640, 480, 704))
    }

    @Test
    fun `light code on dark ground`() {
        val qr = MultiFormatWriter().encode("4711-01", BarcodeFormat.QR_CODE, 240, 240)
        assertEquals("4711-01", CodeDecoder(CodeFormat.QR_ONLY).decode(frame(qr, 480, 360, 480, invert = true), 480, 360))
    }

    @Test
    fun `barcode held upright in portrait`() {
        // Bars running along the sensor's rows: the frame must be turned to read it.
        val bars = MultiFormatWriter().encode("4711-02", BarcodeFormat.CODE_128, 360, 120)
        val turned = BitMatrix(bars.height, bars.width)
        for (y in 0 until bars.height) for (x in 0 until bars.width) if (bars[x, y]) turned.set(y, bars.width - 1 - x)
        assertEquals("4711-02", CodeDecoder(CodeFormat.LABELS).decode(frame(turned, 480, 640, 480), 480, 640))
    }

    @Test
    fun `nothing in the picture`() {
        assertNull(CodeDecoder(CodeFormat.LABELS).decode(ByteArray(320 * 240) { 0x80.toByte() }, 320, 240))
    }
}
