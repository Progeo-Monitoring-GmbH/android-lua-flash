package com.progeo.core.permission

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** The app's page in the system settings, where a permission denied for good can be granted. */
fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * Shows [content] once the runtime [permission] is granted. Until then it explains why the app
 * needs it ([rationale]) and asks; after "Don't ask again" it leads to the system settings.
 */
@Composable
fun PermissionGate(
    permission: String,
    rationale: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasPermission(permission)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    // Back from the system settings: the permission may be granted now.
    LifecycleResumeEffect(permission) {
        granted = context.hasPermission(permission)
        onPauseOrDispose {}
    }
    if (granted) {
        content()
        return
    }
    // Asked, refused, and the system no longer shows the dialog: only the settings can help.
    val blocked = asked && context.activity()?.shouldShowRequestPermissionRationale(permission) == false
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(rationale, color = contentColor, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (blocked) {
            Text("The permission was denied. Allow it in the app settings.", color = contentColor, textAlign = TextAlign.Center)
            Button(onClick = { context.openAppSettings() }) { Text("Open app settings") }
        } else {
            Button(onClick = { launcher.launch(permission) }) { Text("Allow") }
        }
    }
}
