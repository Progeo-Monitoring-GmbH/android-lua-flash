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
    override fun onReceive(context: Context, intent: Intent) {
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
