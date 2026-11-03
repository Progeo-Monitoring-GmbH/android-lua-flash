package com.progeo.luaflasher.core.esp

import java.io.ByteArrayOutputStream

/** SLIP framing of the ESP8266 ROM bootloader: frames delimited by 0xC0. */
object Slip {
    const val END: Int = 0xC0
    const val ESC: Int = 0xDB
    const val ESC_END: Int = 0xDC
    const val ESC_ESC: Int = 0xDD

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 16)
        out.write(END)
        for (b in payload) {
            when (val v = b.toInt() and 0xFF) {
                END -> { out.write(ESC); out.write(ESC_END) }
                ESC -> { out.write(ESC); out.write(ESC_ESC) }
                else -> out.write(v)
            }
        }
        out.write(END)
        return out.toByteArray()
    }
}

/**
 * Streaming SLIP decoder. Bytes before the first 0xC0 are ignored. Every 0xC0 both ends
 * the current frame and starts the next one, so garbage between frames becomes a junk
 * frame that the response parser drops, and a stray 0xC0 in garbage cannot swallow the
 * start of a real frame. Frames with an invalid escape are dropped.
 */
class SlipDecoder {
    private val buffer = ByteArrayOutputStream()
    private var started = false
    private var escape = false
    private var broken = false

    fun reset() {
        buffer.reset()
        started = false
        escape = false
        broken = false
    }

    fun feed(data: ByteArray, onFrame: (ByteArray) -> Unit) {
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v == Slip.END) {
                if (started && buffer.size() > 0 && !broken && !escape) onFrame(buffer.toByteArray())
                buffer.reset()
                started = true
                escape = false
                broken = false
                continue
            }
            if (!started) continue
            if (escape) {
                escape = false
                when (v) {
                    Slip.ESC_END -> buffer.write(Slip.END)
                    Slip.ESC_ESC -> buffer.write(Slip.ESC)
                    else -> broken = true
                }
            } else if (v == Slip.ESC) {
                escape = true
            } else {
                buffer.write(v)
            }
        }
    }
}
