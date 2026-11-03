package com.progeo.luaflasher.core.esp

import com.progeo.luaflasher.core.NoSyncException
import com.progeo.luaflasher.core.RomErrorException
import com.progeo.luaflasher.core.RomTimeoutException
import com.progeo.luaflasher.core.WrongChipException
import com.progeo.luaflasher.core.io.SerialPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Talks to the ESP8266 ROM bootloader, without flasher stub (PLAN.md section 5).
 * Checked against esptool 4.8.1 loader.py / targets/esp8266.py.
 */
class RomLoader(
    private val port: SerialPort,
    private val trace: (String) -> Unit = {},
) {
    private val decoder = SlipDecoder()
    private val frames = ArrayDeque<ByteArray>()

    suspend fun purge() {
        port.purge()
        decoder.reset()
        frames.clear()
    }

    /** Classic DTR/RTS reset into the bootloader for boards that have the circuit (esptool ClassicReset). */
    suspend fun classicReset() {
        port.setDtr(false)
        port.setRts(true)
        delay(100)
        port.setDtr(true)
        port.setRts(false)
        delay(50)
        port.setDtr(false)
    }

    private suspend fun nextFrame(timeoutMs: Long): ByteArray? = withTimeoutOrNull(timeoutMs) {
        while (frames.isEmpty()) {
            val data = port.read(timeoutMs)
            if (data.isNotEmpty()) decoder.feed(data) { frames.addLast(it) }
        }
        frames.removeFirst()
    }

    private suspend fun send(op: Int, payload: ByteArray, checksum: Int) {
        trace("-> op=0x%02X len=%d".format(op, payload.size))
        port.write(Slip.encode(RomProtocol.request(op, payload, checksum)))
    }

    /** Waits for the response to [op]; responses to other commands are dropped. Null on timeout. */
    private suspend fun awaitResponse(op: Int, timeoutMs: Long): RomResponse? = withTimeoutOrNull(timeoutMs) {
        var result: RomResponse? = null
        while (result == null) {
            val frame = nextFrame(timeoutMs) ?: continue
            val response = RomProtocol.parseResponse(frame) ?: continue
            if (response.op == op) result = response else trace("<- dropped response op=0x%02X".format(response.op))
        }
        result
    }

    /** Sends a command and returns the response; throws on timeout or a non-zero status. */
    suspend fun command(op: Int, payload: ByteArray, checksum: Int = 0, timeoutMs: Long = DEFAULT_TIMEOUT_MS): RomResponse {
        send(op, payload, checksum)
        val response = awaitResponse(op, timeoutMs) ?: throw RomTimeoutException(op)
        if (response.status != 0) throw RomErrorException(op, response.status, response.error)
        return response
    }

    /**
     * SYNC every [intervalMs] until the ROM answers, for at most [attempts] tries; then drains
     * the extra SYNC replies the ROM sends. Call right after the user pressed RST.
     */
    suspend fun sync(attempts: Int = 100, intervalMs: Long = 100) {
        val payload = RomProtocol.syncPayload()
        repeat(attempts) { attempt ->
            send(Op.SYNC, payload, 0)
            val response = awaitResponse(Op.SYNC, intervalMs)
            if (response != null) {
                trace("sync after ${attempt + 1} attempt(s)")
                // esptool reads 7 more replies; the ROM answers each SYNC up to 8 times.
                while (nextFrame(SYNC_DRAIN_MS) != null) Unit
                return
            }
        }
        throw NoSyncException()
    }

    suspend fun readReg(address: Long): Long =
        command(Op.READ_REG, RomProtocol.readRegPayload(address)).value

    /** Checks the chip and reads its MAC (esptool esp8266.py read_mac). */
    suspend fun detectChip(): ChipInfo {
        val magic = readReg(Esp8266.CHIP_DETECT_MAGIC_REG)
        if (magic != Esp8266.CHIP_DETECT_MAGIC_VALUE) throw WrongChipException(magic)
        val mac0 = readReg(Esp8266.OTP_MAC0)
        val mac1 = readReg(Esp8266.OTP_MAC1)
        val mac3 = readReg(Esp8266.OTP_MAC3)
        return ChipInfo(mac = Esp8266.mac(mac0, mac1, mac3))
    }

    suspend fun flashBegin(eraseSize: Long, numBlocks: Int, offset: Long, timeoutMs: Long) {
        command(
            Op.FLASH_BEGIN,
            RomProtocol.flashBeginPayload(eraseSize, numBlocks, RomProtocol.FLASH_WRITE_SIZE, offset),
            timeoutMs = timeoutMs,
        )
    }

    /** [block] must be padded to [RomProtocol.FLASH_WRITE_SIZE]; the checksum covers the padded block. */
    suspend fun flashData(block: ByteArray, seq: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        command(Op.FLASH_DATA, RomProtocol.flashDataPayload(block, seq), RomProtocol.checksum(block), timeoutMs)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
        private const val SYNC_DRAIN_MS = 100L

        /** esptool timeout_per_mb: seconds per MB, at least the default timeout. */
        fun timeoutPerMb(secondsPerMb: Double, sizeBytes: Long): Long =
            maxOf(DEFAULT_TIMEOUT_MS, (secondsPerMb * sizeBytes / 1e6 * 1000).toLong())
    }
}

data class ChipInfo(val mac: String?) {
    val description: String get() = "ESP8266" + (mac?.let { ", MAC $it" } ?: "")
}

object Esp8266 {
    /** esptool loader.py CHIP_DETECT_MAGIC_REG_ADDR; PLAN.md's 0x3FF0007C is not used by esptool. */
    const val CHIP_DETECT_MAGIC_REG = 0x40001000L
    const val CHIP_DETECT_MAGIC_VALUE = 0xFFF0C101L
    const val OTP_MAC0 = 0x3FF00050L
    const val OTP_MAC1 = 0x3FF00054L
    const val OTP_MAC3 = 0x3FF0005CL

    /** MAC as in esptool esp8266.py read_mac, or null for an unknown OUI. */
    fun mac(mac0: Long, mac1: Long, mac3: Long): String? {
        val oui = when {
            mac3 != 0L -> listOf((mac3 shr 16) and 0xFF, (mac3 shr 8) and 0xFF, mac3 and 0xFF)
            ((mac1 shr 16) and 0xFF) == 0L -> listOf(0x18L, 0xFEL, 0x34L)
            ((mac1 shr 16) and 0xFF) == 1L -> listOf(0xACL, 0xD0L, 0x74L)
            else -> return null
        }
        val bytes = oui + listOf((mac1 shr 8) and 0xFF, mac1 and 0xFF, (mac0 shr 24) and 0xFF)
        return bytes.joinToString(":") { "%02x".format(it) }
    }
}
