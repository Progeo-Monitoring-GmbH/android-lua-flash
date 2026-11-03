package com.progeo.luaflasher

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.progeo.luaflasher.ui.AppNavigation
import com.progeo.luaflasher.ui.theme.LuaFlasherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For the progress notification while flashing; flashing works without it.
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { LuaFlasherTheme { AppNavigation() } }
    }

    override fun onResume() {
        super.onResume()
        // Opened by USB_DEVICE_ATTACHED or back from the permission dialog.
        graph.usb.refresh()
    }
}
