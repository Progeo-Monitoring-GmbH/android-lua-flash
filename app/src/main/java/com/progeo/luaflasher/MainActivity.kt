package com.progeo.luaflasher

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.progeo.core.ui.login.AuthGate
import com.progeo.core.ui.theme.ProGeoTheme
import com.progeo.luaflasher.data.AppSettings
import com.progeo.luaflasher.ui.AppNavigation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The corporate design is light only: dark status and navigation bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // For the progress notification while flashing; flashing works without it.
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ProGeoTheme {
                val settings by graph.settings.settings.collectAsState(AppSettings())
                // The app starts with the login; it stays logged in (tokens in the Keystore).
                AuthGate(
                    backend = graph.backend,
                    appName = getString(R.string.app_name),
                    appVersion = BuildConfig.VERSION_NAME,
                    serverLabel = settings.backendUrl
                        .takeIf { it.trimEnd('/') != AppSettings.DEFAULT_BACKEND_URL.trimEnd('/') }
                        ?.let { "Server: ${it.removePrefix("https://").trimEnd('/')}" },
                ) { AppNavigation() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Opened by USB_DEVICE_ATTACHED or back from the permission dialog.
        graph.usb.refresh()
    }
}
