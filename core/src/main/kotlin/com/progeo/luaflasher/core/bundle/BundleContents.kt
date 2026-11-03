package com.progeo.luaflasher.core.bundle

import com.progeo.luaflasher.core.esp.FlashSegment
import com.progeo.luaflasher.core.nodemcu.InstallPayload
import com.progeo.luaflasher.core.nodemcu.RemoteFile

/** Device path of the generated config (PLAN.md section 7). */
const val DEVICE_CONFIG_PATH = "config/device_config.lua"

/** Firmware segments, written unchanged (no header patching). */
fun Bundle.flashSegments(): List<FlashSegment> =
    manifest.firmware.segments
        .sortedBy { it.addressValue }
        .map { FlashSegment(it.addressValue, bytes(it.file), it.file) }

fun Bundle.installPayload(deviceConfigLua: String): InstallPayload = InstallPayload(
    bundleId = manifest.bundleId,
    firmwareVersion = manifest.firmware.version,
    lfsImage = bytes(manifest.lfs.file),
    lfsModules = manifest.lfs.modules,
    files = manifest.files.map { RemoteFile(it.path, bytes(it.file)) },
    initLua = RemoteFile(manifest.init.path, bytes(manifest.init.file)),
    deviceConfig = RemoteFile(DEVICE_CONFIG_PATH, deviceConfigLua.toByteArray(Charsets.UTF_8)),
)
