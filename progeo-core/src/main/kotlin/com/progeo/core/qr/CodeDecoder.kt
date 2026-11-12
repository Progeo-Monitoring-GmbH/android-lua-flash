package com.progeo.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/** The codes the scanner looks for. */
enum class CodeFormat(internal val zxing: BarcodeFormat, internal val linear: Boolean) {
    QR(BarcodeFormat.QR_CODE, false),
    DATA_MATRIX(BarcodeFormat.DATA_MATRIX, false),
    CODE_128(BarcodeFormat.CODE_128, true),
    ;

    companion object {
        val QR_ONLY = setOf(QR)

        /** Labels on boards and devices. */
        val LABELS = setOf(QR, DATA_MATRIX, CODE_128)
    }
}

/**
 * Finds a code in one camera frame (the Y plane: one luminance byte per pixel, rows
 * [rowStride] apart). Pure ZXing, no Android: testable on the JVM.
 */
class CodeDecoder(private val formats: Set<CodeFormat>) {
    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to formats.map { it.zxing },
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }
    private val linear = formats.any { it.linear }

    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        decode(source)?.let { return it }
        // Light code on dark ground (codes on screens in dark mode).
        decode(source.invert())?.let { return it }
        // The camera delivers frames in sensor orientation (landscape); a barcode held level in
        // portrait arrives upright. 2D codes do not care, 1D ones need their bars across the row.
        if (linear) {
            val rotated = rotate(luma, width, height, rowStride)
            decode(PlanarYUVLuminanceSource(rotated, height, width, 0, 0, height, width, false))?.let { return it }
        }
        return null
    }

    private fun decode(source: LuminanceSource): String? = try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: NotFoundException) {
        null
    } catch (e: ReaderException) {
        null
    } finally {
        reader.reset()
    }

    /** 90° clockwise; the result is [height] wide and [width] high, without row padding. */
    private fun rotate(luma: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            val row = y * rowStride
            for (x in 0 until width) out[x * height + (height - 1 - y)] = luma[row + x]
        }
        return out
    }
}
