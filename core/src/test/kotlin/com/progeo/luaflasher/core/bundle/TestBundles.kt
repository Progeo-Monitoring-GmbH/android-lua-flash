package com.progeo.luaflasher.core.bundle

import com.progeo.luaflasher.core.Fixtures
import com.progeo.luaflasher.core.io.sha256Hex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** Builds bundle zips in the layout of PLAN.md section 3. */
object TestBundles {
    val boot: ByteArray = Random(10).nextBytes(30064)
    val app: ByteArray = Random(11).nextBytes(60000)
    val lfs: ByteArray = "Relais,connWLAN,control,display,logger,main,measurement,portal,webserver".toByteArray()
    val init: ByteArray = "-- init.lua\n".toByteArray()
    val runtime: ByteArray = "return {\n  x = 1,\n}\n".toByteArray()
    val template: ByteArray = Fixtures.bytes("device_config.lua.template")

    fun manifest(format: Int = 1, configSchema: Int = 1, segments: List<Triple<String, String, ByteArray>>? = null): JsonObject =
        buildJsonObject {
            put("format", format)
            put("bundle_id", "v9.9.9_fw-v1.2.0-tls-lua53")
            put("release", "v9.9.9")
            put("config_schema", configSchema)
            put("git_commit", "abc1234")
            putJsonObject("firmware") {
                put("version", "v1.2.0-tls-lua53")
                put("lfs_size", "0x20000")
                putJsonArray("segments") {
                    for ((address, file, data) in segments ?: listOf(
                        Triple("0x00000", "firmware/0x00000.bin", boot),
                        Triple("0x10000", "firmware/0x10000.bin", app),
                    )) {
                        addJsonObject {
                            put("address", address)
                            put("file", file)
                            put("size", data.size)
                            put("sha256", sha256Hex(data))
                        }
                    }
                }
            }
            putJsonObject("lfs") {
                put("file", "fs/lfs.img")
                put("size", lfs.size)
                put("sha256", sha256Hex(lfs))
                putJsonArray("modules") { listOf("Relais", "main").forEach { add(it) } }
            }
            putJsonArray("files") {
                addJsonObject {
                    put("path", "config/runtime_config.lua")
                    put("file", "fs/config/runtime_config.lua")
                    put("size", runtime.size)
                    put("sha256", sha256Hex(runtime))
                }
            }
            putJsonObject("init") {
                put("path", "init.lua")
                put("file", "fs/init.lua")
                put("size", init.size)
                put("sha256", sha256Hex(init))
            }
            put("device_config_template", "templates/device_config.lua.template")
            put("future_field", "ignored")
        }

    fun entries(manifest: JsonObject = manifest()): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "bundle.json" to Json.encodeToString(JsonObject.serializer(), manifest).toByteArray(),
        "firmware/0x00000.bin" to boot,
        "firmware/0x10000.bin" to app,
        "fs/lfs.img" to lfs,
        "fs/init.lua" to init,
        "fs/config/runtime_config.lua" to runtime,
        "templates/device_config.lua.template" to template,
    )

    fun zip(entries: Map<String, ByteArray> = entries()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
