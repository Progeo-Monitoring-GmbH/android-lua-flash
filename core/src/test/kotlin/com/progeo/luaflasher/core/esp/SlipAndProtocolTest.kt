package com.progeo.luaflasher.core.esp

import com.progeo.luaflasher.core.io.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlipAndProtocolTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun decodeAll(vararg chunks: ByteArray): List<ByteArray> {
        val decoder = SlipDecoder()
        val frames = ArrayList<ByteArray>()
        chunks.forEach { decoder.feed(it) { f -> frames += f } }
        return frames
    }

    @Test
    fun `encode escapes C0 and DB`() {
        assertContentEquals(
            bytes(0xC0, 0x01, 0xDB, 0xDC, 0x02, 0xDB, 0xDD, 0x03, 0xC0),
            Slip.encode(bytes(0x01, 0xC0, 0x02, 0xDB, 0x03)),
        )
    }

    @Test
    fun `decode round trip with C0 and DB in data`() {
        val payload = bytes(0xC0, 0xDB, 0xDC, 0xDD, 0x00, 0xC0, 0xC0)
        val frames = decodeAll(Slip.encode(payload))
        assertEquals(1, frames.size)
        assertContentEquals(payload, frames[0])
    }

    @Test
    fun `garbage before and between frames is ignored or dropped as junk`() {
        val a = Slip.encode(bytes(1, 2, 3))
        val b = Slip.encode(bytes(4, 5))
        val frames = decodeAll(bytes(0x55, 0x12, 0xFF) + a + bytes(0x99, 0x98) + b)
        // The junk between the frames comes out as its own frame and is dropped by parseResponse.
        assertEquals(listOf("010203", "9998", "0405"), frames.map { it.toHex() })
    }

    @Test
    fun `frames split across reads`() {
        val frame = Slip.encode(bytes(1, 0xC0, 3, 4))
        val frames = decodeAll(frame.copyOfRange(0, 2), frame.copyOfRange(2, 3), frame.copyOfRange(3, frame.size))
        assertContentEquals(bytes(1, 0xC0, 3, 4), frames.single())
    }

    @Test
    fun `invalid escape drops the frame`() {
        val frames = decodeAll(bytes(0xC0, 1, 0xDB, 0x01, 2, 0xC0) + Slip.encode(bytes(7)))
        assertEquals(listOf("07"), frames.map { it.toHex() })
    }

    @Test
    fun `checksum starts at EF`() {
        assertEquals(0xEF, RomProtocol.checksum(ByteArray(0)))
        assertEquals(0xEF xor 0x01 xor 0x02, RomProtocol.checksum(bytes(1, 2)))
        assertEquals(0xEF, RomProtocol.checksum(ByteArray(0x400) { 0xFF.toByte() }))
    }

    @Test
    fun `sync packet matches esptool trace`() {
        // esptool --trace: "command op=0x08 data len=36 ... data=07071220 55555555..."
        val expected = "c0" + "0008" + "2400" + "00000000" + "07071220" + "55".repeat(32) + "c0"
        assertEquals(expected, Slip.encode(RomProtocol.request(Op.SYNC, RomProtocol.syncPayload())).toHex())
    }

    @Test
    fun `read_reg packet`() {
        assertEquals(
            "000a0400000000000010004" + "0",
            RomProtocol.request(Op.READ_REG, RomProtocol.readRegPayload(Esp8266.CHIP_DETECT_MAGIC_REG)).toHex(),
        )
    }

    @Test
    fun `flash_begin and flash_data packets`() {
        assertEquals(
            "00401000" + "02000000" + "00040000" + "00000100",
            RomProtocol.flashBeginPayload(0x104000, 2, 0x400, 0x10000).toHex(),
        )
        val block = ByteArray(0x400) { 0xFF.toByte() }.also { it[0] = 0xE9.toByte() }
        val payload = RomProtocol.flashDataPayload(block, 5)
        assertEquals("00040000" + "05000000" + "00000000" + "00000000" + "e9ff", payload.copyOfRange(0, 18).toHex())
        val request = RomProtocol.request(Op.FLASH_DATA, payload, RomProtocol.checksum(block))
        assertEquals("0003" + "1004" + "%02x000000".format(0xEF xor 0xE9 xor 0xFF), request.copyOfRange(0, 8).toHex())
    }

    @Test
    fun `parse response`() {
        val frame = bytes(0x01, 0x0A, 0x02, 0x00, 0x01, 0xC1, 0xF0, 0xFF, 0x00, 0x00)
        val r = RomProtocol.parseResponse(frame)!!
        assertEquals(Op.READ_REG, r.op)
        assertEquals(0xFFF0C101L, r.value)
        assertEquals(0, r.status)
        assertNull(RomProtocol.parseResponse(bytes(0x00, 0x0A, 0x02, 0x00, 0, 0, 0, 0, 0, 0)))
        assertNull(RomProtocol.parseResponse(bytes(0x01, 0x0A, 0x09, 0x00, 0, 0, 0, 0, 0, 0)))
    }

    /** Expected values computed by hand from esptool ESP8266ROM.get_erase_size. */
    @Test
    fun `erase size workaround matches esptool`() {
        val table = listOf(
            Triple(0x00000L, 30064L, 16384L),
            Triple(0x10000L, 606208L, 540672L),
            Triple(0x01000L, 0x1000L, 0x1000L),
            Triple(0x3FC000L, 128L, 0x1000L),
            Triple(0x0F000L, 0x3000L, 0x2000L),
            Triple(0x00000L, 0x20000L, 0x10000L),
            Triple(0x00000L, 0x1F000L, 0x10000L),
            Triple(0x00000L, 0L, 0L),
        )
        for ((offset, size, expected) in table) {
            assertEquals(expected, eraseSize(offset, size), "offset 0x%X size %d".format(offset, size))
        }
    }

    @Test
    fun `mac like esptool read_mac`() {
        assertEquals("18:fe:34:34:56:12", Esp8266.mac(0x12000000L, 0x00003456L, 0L))
        assertEquals("ac:d0:74:34:56:12", Esp8266.mac(0x12000000L, 0x00013456L, 0L))
        assertEquals("aa:bb:cc:34:56:12", Esp8266.mac(0x12000000L, 0x00023456L, 0x00AABBCCL))
        assertNull(Esp8266.mac(0L, 0x00023456L, 0L))
    }
}
