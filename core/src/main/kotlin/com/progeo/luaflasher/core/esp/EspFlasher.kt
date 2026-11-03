package com.progeo.luaflasher.core.esp

import com.progeo.luaflasher.core.FlashWriteException
import com.progeo.luaflasher.core.FlasherException
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/** One image to write unchanged at [address]. */
class FlashSegment(val address: Long, val data: ByteArray, val name: String)

data class FlashProgress(
    val segment: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val etaSeconds: Long?,
) {
    val fraction: Float get() = if (bytesTotal == 0L) 0f else bytesDone.toFloat() / bytesTotal
}

/**
 * Writes segments with FLASH_BEGIN + FLASH_DATA (esptool write_flash without stub and
 * compression). No FLASH_END: the ROM would jump to user code; the user resets the board.
 */
class EspFlasher(
    private val loader: RomLoader,
    private val trace: (String) -> Unit = {},
) {
    suspend fun write(segments: List<FlashSegment>, onProgress: (FlashProgress) -> Unit = {}) {
        val images = segments.map { it to padTo4(it.data) }
        val total = images.sumOf { it.second.size.toLong() }
        var done = 0L
        val started = TimeSource.Monotonic.markNow()

        for ((segment, image) in images) {
            val blockSize = RomProtocol.FLASH_WRITE_SIZE
            val numBlocks = (image.size + blockSize - 1) / blockSize
            val erase = eraseSize(segment.address, image.size.toLong())
            // The ROM erases up front: 30 s per MB (esptool ERASE_REGION_TIMEOUT_PER_MB).
            val beginTimeout = RomLoader.timeoutPerMb(30.0, image.size.toLong())
            trace("segment ${segment.name} at 0x%05X: %d bytes, %d blocks, erase %d".format(segment.address, image.size, numBlocks, erase))
            try {
                loader.flashBegin(erase, numBlocks, segment.address, beginTimeout)
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlasherException) {
                throw FlashWriteException(segment.address, e)
            }

            for (seq in 0 until numBlocks) {
                val from = seq * blockSize
                val block = ByteArray(blockSize) { 0xFF.toByte() }
                image.copyInto(block, 0, from, minOf(from + blockSize, image.size))
                writeBlock(block, seq, segment.address + from)
                done += minOf(blockSize, image.size - from)
                val elapsed = started.elapsedNow().inWholeMilliseconds
                val eta = if (done > 0 && elapsed > 2_000) (elapsed * (total - done) / done) / 1000 else null
                onProgress(FlashProgress(segment.name, done, total, eta))
            }
        }
    }

    private suspend fun writeBlock(block: ByteArray, seq: Int, address: Long) {
        var lastError: FlasherException? = null
        repeat(WRITE_BLOCK_ATTEMPTS) { attempt ->
            try {
                loader.flashData(block, seq)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: FlasherException) {
                lastError = e
                trace("block $seq failed (attempt ${attempt + 1}): ${e.message}")
            }
        }
        throw FlashWriteException(address, lastError)
    }

    companion object {
        /** esptool WRITE_BLOCK_ATTEMPTS: one try plus two retries. */
        const val WRITE_BLOCK_ATTEMPTS = 3

        /** esptool pads every image to 4 bytes with 0xFF before writing. */
        fun padTo4(data: ByteArray): ByteArray {
            val rest = data.size % 4
            return if (rest == 0) data else data + ByteArray(4 - rest) { 0xFF.toByte() }
        }
    }
}
