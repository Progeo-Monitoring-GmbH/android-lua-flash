package com.progeo.luaflasher.core.esp

import com.progeo.luaflasher.core.FlashWriteException
import com.progeo.luaflasher.core.NoSyncException
import com.progeo.luaflasher.core.WrongChipException
import com.progeo.luaflasher.core.fakes.FakeEsp8266Rom
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RomLoaderTest {

    @Test
    fun `sync and chip detection with garbage on the line`() = runTest {
        val rom = FakeEsp8266Rom(garbage = byteArrayOf(0x12, 0xC0.toByte(), 0x77, 0x00), syncsToIgnore = 5)
        val loader = RomLoader(rom)
        loader.sync()
        val chip = loader.detectChip()
        assertEquals("18:fe:34:34:56:12", chip.mac)
        assertEquals("ESP8266, MAC 18:fe:34:34:56:12", chip.description)
    }

    @Test
    fun `no sync when the board is not in bootloader mode`() = runTest {
        val loader = RomLoader(FakeEsp8266Rom(inBootloader = false))
        assertFailsWith<NoSyncException> { loader.sync() }
        // 100 attempts x 100 ms
        assertTrue(testScheduler.currentTime in 9_000L..11_000L, "took ${testScheduler.currentTime} ms")
    }

    @Test
    fun `wrong chip`() = runTest {
        val loader = RomLoader(FakeEsp8266Rom(magic = 0x00F01D83L))
        loader.sync()
        assertFailsWith<WrongChipException> { loader.detectChip() }
    }

    @Test
    fun `writes segments unchanged with esptool erase sizes`() = runTest {
        val rom = FakeEsp8266Rom()
        val loader = RomLoader(rom)
        loader.sync()
        val random = Random(42)
        val boot = random.nextBytes(30064)
        val app = random.nextBytes(606208 - 3) // not a multiple of 4 or of the block size
        val progress = ArrayList<FlashProgress>()
        EspFlasher(loader).write(
            listOf(FlashSegment(0x00000, boot, "0x00000.bin"), FlashSegment(0x10000, app, "0x10000.bin")),
        ) { progress += it }

        assertContentEquals(boot, rom.flashBytes(0x00000, boot.size))
        assertContentEquals(app, rom.flashBytes(0x10000, app.size))
        // esptool pads to 4 bytes with 0xFF, the rest of the last block is 0xFF too
        assertEquals(0xFF.toByte(), rom.flash[0x10000L + app.size])

        val (b0, b1) = rom.begins
        assertEquals(FakeEsp8266Rom.Begin(16384, 30, 0x400, 0x00000), b0)
        assertEquals(FakeEsp8266Rom.Begin(eraseSize(0x10000, 606208), 592, 0x400, 0x10000), b1)
        assertEquals(progress.last().bytesTotal, progress.last().bytesDone)
        assertEquals(30064L + 606208L, progress.last().bytesTotal)
    }

    @Test
    fun `retries a block whose response got lost`() = runTest {
        val rom = FakeEsp8266Rom()
        rom.dropDataResponses[3] = 2
        val loader = RomLoader(rom)
        loader.sync()
        val data = Random(1).nextBytes(8 * 1024)
        EspFlasher(loader).write(listOf(FlashSegment(0, data, "x")))
        assertContentEquals(data, rom.flashBytes(0, data.size))
        assertEquals(3, rom.dataSeqs.count { it == 3 })
    }

    @Test
    fun `gives up after three attempts and names the address`() = runTest {
        val rom = FakeEsp8266Rom()
        rom.dropDataResponses[2] = 10
        val loader = RomLoader(rom)
        loader.sync()
        val e = assertFailsWith<FlashWriteException> {
            EspFlasher(loader).write(listOf(FlashSegment(0x10000, ByteArray(8 * 1024), "x")))
        }
        assertEquals("Writing failed at 0x10800. Press RST (jumper set) and retry; the board stays recoverable.", e.userMessage)
        assertEquals(3, rom.dataSeqs.count { it == 2 })
    }

    @Test
    fun `rom error status aborts the segment`() = runTest {
        val rom = FakeEsp8266Rom()
        rom.failData[0] = 0x07
        val loader = RomLoader(rom)
        loader.sync()
        assertFailsWith<FlashWriteException> { EspFlasher(loader).write(listOf(FlashSegment(0, ByteArray(100), "x"))) }
    }
}
