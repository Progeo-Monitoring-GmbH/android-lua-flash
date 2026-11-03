package com.progeo.luaflasher.core.io

import java.security.MessageDigest

/** Little-endian helpers for the ROM bootloader protocol. */
internal fun ByteArray.putU16(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun ByteArray.putU32(offset: Int, value: Long) {
    for (i in 0 until 4) this[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
}

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

internal fun ByteArray.u16(offset: Int): Int = u8(offset) or (u8(offset + 1) shl 8)

internal fun ByteArray.u32(offset: Int): Long =
    (u8(offset).toLong()) or (u8(offset + 1).toLong() shl 8) or
        (u8(offset + 2).toLong() shl 16) or (u8(offset + 3).toLong() shl 24)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

fun Long.hex32(): String = "0x%08X".format(this)

fun Int.hexAddress(): String = "0x%05X".format(this)
