package com.progeo.luaflasher.core.fakes

import com.progeo.luaflasher.core.esp.Esp8266
import com.progeo.luaflasher.core.esp.Op
import com.progeo.luaflasher.core.esp.RomProtocol
import com.progeo.luaflasher.core.esp.Slip
import com.progeo.luaflasher.core.esp.SlipDecoder
import com.progeo.luaflasher.core.io.SerialPort
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Simulates the ESP8266 ROM bootloader behind a serial port. */
class FakeEsp8266Rom(
    var inBootloader: Boolean = true,
    var magic: Long = Esp8266.CHIP_DETECT_MAGIC_VALUE,
    /** Garbage sent before every response (boot text at the wrong baud rate). */
    var garbage: ByteArray = byteArrayOf(),
    /** Number of SYNCs ignored before the ROM answers (user pressed RST a bit late). */
    var syncsToIgnore: Int = 0,
) : SerialPort {
    data class Begin(val eraseSize: Long, val numBlocks: Long, val blockSize: Long, val offset: Long)

    val flash = HashMap<Long, Byte>()
    val begins = ArrayList<Begin>()
    val dataSeqs = ArrayList<Int>()

    /** seq -> how many times to drop the response for that block. */
    val dropDataResponses = HashMap<Int, Int>()

    /** seq -> error code to answer with (always). */
    val failData = HashMap<Int, Int>()

    val registers = HashMap<Long, Long>().apply {
        put(Esp8266.OTP_MAC0, 0x12000000L)
        put(Esp8266.OTP_MAC1, 0x00003456L)
        put(Esp8266.OTP_MAC3, 0L)
    }

    private val output = Channel<ByteArray>(Channel.UNLIMITED)
    private val decoder = SlipDecoder()
    private var current: Begin? = null
    private var baud = 115200

    override suspend fun write(data: ByteArray) {
        decoder.feed(data) { handle(it) }
    }

    override suspend fun read(timeoutMs: Long): ByteArray =
        withTimeoutOrNull(timeoutMs) { output.receive() } ?: ByteArray(0)

    override suspend fun setBaudRate(baud: Int) { this.baud = baud }
    override suspend fun setDtr(value: Boolean) = Unit
    override suspend fun setRts(value: Boolean) = Unit
    override suspend fun purge() {
        while (output.tryReceive().isSuccess) Unit
    }

    private fun respond(op: Int, value: Long = 0, status: Int = 0, error: Int = 0) {
        val data = byteArrayOf(status.toByte(), error.toByte())
        val frame = ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(0x01).put(op.toByte()).putShort(data.size.toShort()).putInt(value.toInt()).put(data)
        output.trySend(garbage + Slip.encode(frame.array()))
    }

    private fun handle(frame: ByteArray) {
        if (!inBootloader) return
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.get().toInt() != 0) return
        val op = buf.get().toInt() and 0xFF
        val size = buf.short.toInt() and 0xFFFF
        val checksum = buf.int.toLong() and 0xFFFFFFFFL
        val payload = frame.copyOfRange(8, frame.size)
        check(payload.size == size) { "size field $size != payload ${payload.size}" }
        val p = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        when (op) {
            Op.SYNC -> {
                check(payload.contentEquals(RomProtocol.syncPayload()))
                if (syncsToIgnore > 0) {
                    syncsToIgnore--
                    return
                }
                // The ROM answers a SYNC several times.
                repeat(8) { respond(Op.SYNC, value = 0x20120707) }
            }
            Op.READ_REG -> {
                val address = p.int.toLong() and 0xFFFFFFFFL
                val value = if (address == Esp8266.CHIP_DETECT_MAGIC_REG) magic else registers[address] ?: 0
                respond(Op.READ_REG, value = value)
            }
            Op.FLASH_BEGIN -> {
                val begin = Begin(p.uint(), p.uint(), p.uint(), p.uint())
                begins += begin
                current = begin
                respond(Op.FLASH_BEGIN)
            }
            Op.FLASH_DATA -> {
                val len = p.uint()
                val seq = p.uint().toInt()
                p.uint(); p.uint()
                val block = payload.copyOfRange(16, payload.size)
                check(len.toInt() == block.size) { "len $len != block ${block.size}" }
                check(block.size == RomProtocol.FLASH_WRITE_SIZE) { "block not padded" }
                check(checksum.toInt() == RomProtocol.checksum(block)) { "bad checksum" }
                failData[seq]?.let { respond(Op.FLASH_DATA, status = 1, error = it); return }
                val begin = current ?: error("FLASH_DATA without FLASH_BEGIN")
                val base = begin.offset + seq.toLong() * begin.blockSize
                block.forEachIndexed { i, b -> flash[base + i] = b }
                dataSeqs += seq
                val drops = dropDataResponses[seq] ?: 0
                if (drops > 0) {
                    dropDataResponses[seq] = drops - 1
                    return
                }
                respond(Op.FLASH_DATA)
            }
            else -> respond(op, status = 1, error = RomProtocol.ROM_INVALID_RECV_MSG)
        }
    }

    fun flashBytes(offset: Long, size: Int): ByteArray = ByteArray(size) { flash[offset + it] ?: error("not written: ${offset + it}") }

    private fun ByteBuffer.uint(): Long = int.toLong() and 0xFFFFFFFFL
}
