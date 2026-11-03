package com.progeo.luaflasher.core

/**
 * Failures the user can act on. [userMessage] is shown as is (PLAN.md section 11);
 * [message] carries the technical detail for logs.
 */
open class FlasherException(
    val userMessage: String,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: userMessage, cause)

// --- Phase A: ROM bootloader ---

class NoSyncException : FlasherException(
    "The ESP does not answer. Set the boot jumper, press RST, Retry. Does the board have its own power supply?",
)

class WrongChipException(magic: Long) : FlasherException(
    "Connected chip is not an ESP8266.",
    "Chip detect register returned 0x%08X".format(magic),
)

class RomTimeoutException(op: Int) : FlasherException(
    "The ESP stopped answering. Check the cable and the power supply, press RST (jumper set) and retry.",
    "Timeout waiting for the response to command 0x%02X".format(op),
)

class RomErrorException(op: Int, status: Int, error: Int) : FlasherException(
    "The ESP bootloader reported an error (0x%02X). Press RST (jumper set) and retry.".format(error),
    "Command 0x%02X failed: status %d error 0x%02X".format(op, status, error),
)

class FlashWriteException(address: Long, cause: Throwable?) : FlasherException(
    "Writing failed at 0x%05X. Press RST (jumper set) and retry; the board stays recoverable.".format(address),
    "Writing failed at 0x%05X: %s".format(address, cause?.message),
    cause,
)

// --- Phase B: NodeMCU console ---

class NoPromptException : FlasherException(
    "No answer from NodeMCU. Remove the jumper, press RST. Check the power supply.",
)

class LuaCommandException(command: String, error: String) : FlasherException(
    "The board reported an error: $error",
    "Lua command failed: $command -> $error",
)

class LuaTimeoutException(command: String) : FlasherException(
    "The board stopped answering. Check the cable and the power supply, then retry.",
    "Timeout waiting for the result of: $command",
)

class UploadException(path: String, detail: String) : FlasherException(
    "Uploading $path failed. Retry; the install can be repeated safely.",
    "Upload of $path failed: $detail",
)

class LfsReloadException(error: String) : FlasherException(
    "The code image does not match the firmware on the board. Run Full install.",
    "node.LFS.reload failed: $error",
)

class WrongFirmwareException(found: String) : FlasherException(
    "The firmware on the board does not fit this bundle. Run Full install.",
    "Board reports: $found",
)

class DeviceConfigException(error: String) : FlasherException(
    "The device config is invalid: $error",
)

// --- Bundles ---

class SignatureException(detail: String) : FlasherException(
    "Download is not genuine and was deleted.",
    detail,
)

class BundleException(userMessage: String, detail: String? = null) : FlasherException(userMessage, detail)

class AppTooOldException(detail: String) : FlasherException(
    "Update the app to use this release.",
    detail,
)
