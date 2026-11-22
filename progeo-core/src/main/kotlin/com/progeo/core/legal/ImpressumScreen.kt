package com.progeo.core.legal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.progeo.core.ui.BrandHeader
import com.progeo.core.ui.BrandPanel
import com.progeo.core.ui.theme.ProGeoColors

/** Third-party software in every ProGeo app (via progeo-core); apps may add their own. */
val CoreLicenses = listOf(
    "Plus Jakarta Sans (font)" to "SIL Open Font License 1.1",
    "AndroidX, Jetpack Compose, CameraX" to "Apache License 2.0",
    "ZXing (QR scanner)" to "Apache License 2.0",
    "OkHttp" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache License 2.0",
)

/**
 * Impressum and open-source notices. [extraLicenses]: the app's own third-party software.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImpressumScreen(
    appName: String,
    appVersion: String,
    onBack: () -> Unit,
    imprint: Imprint = Imprint.ProGeo,
    extraLicenses: List<Pair<String, String>> = emptyList(),
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showOfl by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Impressum") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandPanel {
                BrandHeader(appName, Modifier.align(Alignment.CenterHorizontally))
                Text("$appName $appVersion", style = MaterialTheme.typography.bodySmall, color = ProGeoColors.TextMuted, modifier = Modifier.align(Alignment.CenterHorizontally))
                imprint.sections.forEach { (title, lines) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                TextButton(onClick = { uriHandler.openUri(imprint.sourceUrl) }) { Text(imprint.sourceUrl.removePrefix("https://")) }
            }
            BrandPanel {
                Text("Open-source software", style = MaterialTheme.typography.titleSmall)
                (CoreLicenses + extraLicenses).forEach { (name, license) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(license, style = MaterialTheme.typography.bodySmall, color = ProGeoColors.TextMuted)
                    }
                }
                TextButton(onClick = { showOfl = !showOfl }) { Text(if (showOfl) "Hide the font license" else "Show the font license (OFL 1.1)") }
                if (showOfl) {
                    val text = remember {
                        runCatching { context.assets.open("progeo-licenses/OFL-PlusJakartaSans.txt").use { String(it.readBytes()) } }
                            .getOrDefault("SIL Open Font License 1.1: https://openfontlicense.org")
                    }
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
