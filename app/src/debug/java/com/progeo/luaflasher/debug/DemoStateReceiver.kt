package com.progeo.luaflasher.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.progeo.luaflasher.core.esp.FlashProgress
import com.progeo.luaflasher.core.nodemcu.InstallMode
import com.progeo.luaflasher.core.nodemcu.InstallProgress
import com.progeo.luaflasher.core.nodemcu.InstallResult
import com.progeo.luaflasher.core.nodemcu.InstallStep
import com.progeo.luaflasher.flash.FlashState
import com.progeo.luaflasher.flash.Phase
import com.progeo.luaflasher.graph
import kotlinx.coroutines.launch

/**
 * Debug builds only (src/debug): puts the flash wizard into a given step with example data,
 * so every wizard screen can be looked at without a board. Open the wizard first, then:
 *
 *     adb shell am broadcast -n com.progeo.luaflasher/.debug.DemoStateReceiver --es state writing
 *
 * States: idle, boot, syncing, writing, normal, installing, done, failed.
 */
class DemoStateReceiver : BroadcastReceiver() {
    private companion object {
        const val TAG = "DemoStateReceiver"

        /** The picture's luminance, decoded with the app's own scanner code. */
        fun decodeQr(file: java.io.File): String? {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.path) ?: return null
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            val luma = ByteArray(w * h) { i ->
                val p = pixels[i]
                ((((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000).toByte()
            }
            return com.progeo.core.qr.CodeDecoder(com.progeo.core.qr.CodeFormat.QR_ONLY).decode(luma, w, h)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // --es login_qr <file name in the app's cache dir>: logs in with a picture of a login QR
        // code, exactly as if the camera had scanned it (tools/screenshots.ps1). The picture is
        // decoded here and deleted at once, so the token is never on a command line or in a log.
        intent.getStringExtra("login_qr")?.let { name ->
            val file = java.io.File(context.cacheDir, name)
            val pending = goAsync()
            context.graph.appScope.launch {
                try {
                    val text = decodeQr(file)
                    val code = text?.let { com.progeo.core.auth.LoginCode.parse(it) }
                    if (code == null) {
                        android.util.Log.e(TAG, "login_qr: no ProGeo login code in the picture")
                    } else {
                        val result = context.graph.backend.login(code)
                        // LoginResult.Failed carries only a user message, never the token.
                        android.util.Log.i(TAG, "login_qr: $result")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "login_qr failed: ${e.javaClass.simpleName}")
                } finally {
                    file.delete()
                    pending.finish()
                }
            }
            return
        }
        // --es demo_profile <name>: saves an example profile (template defaults plus a few values),
        // standing in for a scanned config QR code, which an emulator cannot scan.
        intent.getStringExtra("demo_profile")?.let { name ->
            val pending = goAsync()
            context.graph.appScope.launch {
                try {
                    val t = context.graph.currentTemplate()
                    var config = t.defaults()
                    fun put(path: String, text: String) {
                        com.progeo.luaflasher.core.config.LuaJson.typed(text, t.field(path)?.kind ?: com.progeo.luaflasher.core.config.FieldKind.STRING)
                            ?.let { config = com.progeo.luaflasher.core.config.LuaJson.with(config, path, it) }
                    }
                    put("wifi.ssid", "Site-WLAN")
                    put("wifi.pwd", "secret123")
                    put("device.version", "2026.11")
                    put("device.project_id", "4711")
                    put("device.device_hash", name)
                    context.graph.profiles.save(com.progeo.luaflasher.data.Profile(name = name, config = config))
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "demo_profile failed", e)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        // --es import <file name in the app's cache dir>: developer import of an (unsigned) bundle,
        // the same as Bundles → Import bundle… (for devices where the file picker is awkward).
        intent.getStringExtra("import")?.let { name ->
            val file = java.io.File(context.cacheDir, name)
            val pending = goAsync()
            context.graph.appScope.launch {
                try {
                    context.graph.bundles.add(file, signed = false, keyId = null, notes = "Imported in developer mode")
                } catch (e: Exception) {
                    android.util.Log.e("DemoStateReceiver", "import failed", e)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val chip = "ESP8266, MAC 18:fe:34:a1:b2:c3"
        val bootLog = """
            NodeMCU 3.0.0.0 built with Docker
            	branch: release
            	commit: v1.6.1-tls-lua53
            > LFS: 10 modules
            BOOT heap: 31234
            connWLAN: connecting to Site-WLAN
            connWLAN: got IP 192.168.1.57
            control: poll in 14400 s
        """.trimIndent()
        val state = when (intent.getStringExtra("state")) {
            "boot" -> FlashState.AwaitBootMode
            "syncing" -> FlashState.Syncing
            "writing" -> FlashState.Writing(chip, FlashProgress("firmware/0x10000.bin", 268_000, 636_272, 38))
            "normal" -> FlashState.AwaitNormalMode(chip)
            "installing" -> FlashState.Installing(InstallProgress(InstallStep.UPLOAD_LFS, 0.6f, "51220 bytes"))
            "done" -> FlashState.Done(InstallResult(bootLog, success = true, bootFailed = null), InstallMode.FULL)
            "failed" -> FlashState.Failed(
                "The ESP does not answer. Set the boot jumper, press RST, Retry. Does the board have its own power supply?",
                null,
                Phase.FIRMWARE,
            )
            else -> FlashState.Idle
        }
        context.graph.flash.showDemoState(state, "## Demo state: ${intent.getStringExtra("state")}\n$bootLog\n")
    }
}
