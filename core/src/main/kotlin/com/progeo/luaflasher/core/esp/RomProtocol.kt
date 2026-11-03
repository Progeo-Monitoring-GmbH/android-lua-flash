package com.progeo.luaflasher.core.esp

import com.progeo.luaflasher.core.io.putU16
import com.progeo.luaflasher.core.io.putU32
import com.progeo.luaflasher.core.io.u16
import com.progeo.luaflasher.core.io.u32
import com.progeo.luaflasher.core.io.u8

/** Commands of the ESP8266 ROM bootloader (esptool loader.py). */
object Op {
    const val FLASH_BEGIN = 0x02
    const val FLASH_DATA = 0x03
    const val FLASH_END = 0x04
    const val SYNC = 0x08
    const val READ_REG = 0x0A
}

object RomProtocol {
    const val CHECKSUM_MAGIC = 0xEF
    const val FLASH_WRITE_SIZE = 0x400
    const val FLASH_SECTOR_SIZE = 0x1000

    /** The ROM returns 2 status bytes at the end of the response data on the ESP8266. */
    const val STATUS_BYTES = 2

    /** Response code the ROM sends for a message it did not understand. */
    const val ROM_INVALID_RECV_MSG = 0x05

    /** Start 0xEF, XOR of all data bytes. */
    fun checksum(data: ByteArray, offset: Int = 0, length: Int = data.size): Int {
        var state = CHECKSUM_MAGIC
        for (i in offset until offset + length) state = state xor (data[i].toInt() and 0xFF)
        return state
    }

    /** Unframed request: 0x00, op, size u16, checksum u32, payload. */
    fun request(op: Int, payload: ByteArray, checksum: Int = 0): ByteArray {
        val packet = ByteArray(8 + payload.size)
        packet[0] = 0x00
        packet[1] = op.toByte()
        packet.putU16(2, payload.size)
        packet.putU32(4, checksum.toLong() and 0xFFFFFFFFL)
        payload.copyInto(packet, 8)
        return packet
    }

    fun syncPayload(): ByteArray = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }

    fun readRegPayload(address: Long): ByteArray = ByteArray(4).also { it.putU32(0, address) }

    fun flashBeginPayload(eraseSize: Long, numBlocks: Int, blockSize: Int, offset: Long): ByteArray =
        ByteArray(16).also {
            it.putU32(0, eraseSize)
            it.putU32(4, numBlocks.toLong())
            it.putU32(8, blockSize.toLong())
            it.putU32(12, offset)
        }

    /** Header (len, seq, 0, 0) + block; the block must already be padded. */
    fun flashDataPayload(block: ByteArray, seq: Int): ByteArray {
        val payload = ByteArray(16 + block.size)
        payload.putU32(0, block.size.toLong())
        payload.putU32(4, seq.toLong())
        block.copyInto(payload, 16)
        return payload
    }

    /** Parses a decoded frame, or null if it is not a response. */
    fun parseResponse(frame: ByteArray): RomResponse? {
        if (frame.size < 8 || frame.u8(0) != 0x01) return null
        val size = frame.u16(2)
        if (8 + size > frame.size) return null
        return RomResponse(op = frame.u8(1), value = frame.u32(4), data = frame.copyOfRange(8, 8 + size))
    }
}

class RomResponse(val op: Int, val value: Long, val data: ByteArray) {
    /** 0 = OK. */
    val status: Int get() = if (data.size >= RomProtocol.STATUS_BYTES) data[data.size - 2].toInt() and 0xFF else -1
    val error: Int get() = if (data.size >= RomProtocol.STATUS_BYTES) data[data.size - 1].toInt() and 0xFF else -1
}

/**
 * Erase size for FLASH_BEGIN with the workaround for the ESP8266 ROM erase bug
 * (esptool targets/esp8266.py get_erase_size).
 */
fun eraseSize(offset: Long, size: Long): Long {
    val sectorsPerBlock = 16L
    val sectorSize = RomProtocol.FLASH_SECTOR_SIZE.toLong()
    val numSectors = (size + sectorSize - 1) / sectorSize
    val startSector = offset / sectorSize
    var headSectors = sectorsPerBlock - (startSector % sectorsPerBlock)
    if (numSectors < headSectors) headSectors = numSectors
    return if (numSectors < 2 * headSectors) {
        (numSectors + 1) / 2 * sectorSize
    } else {
        (numSectors - headSectors) * sectorSize
    }
}
