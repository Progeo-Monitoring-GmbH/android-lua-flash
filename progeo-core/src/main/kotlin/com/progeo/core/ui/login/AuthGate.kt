package com.progeo.core.ui.login

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.progeo.core.auth.AuthState
import com.progeo.core.auth.BackendClient
import com.progeo.core.auth.ModulePermissions
import com.progeo.core.legal.ImpressumScreen
import com.progeo.core.ui.BrandHeader
import com.progeo.core.ui.BrandPanel
import com.progeo.core.ui.CompanyFooter
import kotlinx.coroutines.launch

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * The entry of every ProGeo app: [content] only for a logged-in account that may use the app.
 * Otherwise a screen covers it: the login, or why the account cannot go on (access request
 * under review, two-factor setup missing, [requiredPermissions] missing) with a logout.
 *
 * [content] stays composed underneath, so a session that ends while the user works (refresh
 * token expired) loses no screen state: log in again and carry on.
 *
 * [requiredPermissions]: portal modules the app cannot work without (e.g. `module_devices_enabled`).
 * When the server cannot be reached they are not checked, so the app works offline; the server
 * still refuses every call the account may not make.
 */
@Composable
fun AuthGate(
    backend: BackendClient,
    appName: String,
    appVersion: String,
    requiredPermissions: Set<String> = emptySet(),
    serverLabel: String? = null,
    content: @Composable () -> Unit,
) {
    val auth by backend.state.collectAsState()
    var permissions by remember { mutableStateOf<ModulePermissions?>(null) }
    LaunchedEffect(auth is AuthState.LoggedIn, requiredPermissions) {
        permissions = null
        if (auth is AuthState.LoggedIn && requiredPermissions.isNotEmpty()) {
            permissions = runCatching { backend.modulePermissions() }.getOrNull()
        }
    }

    val blockedBy: String? = when (val a = auth) {
        AuthState.LoggedOut -> null
        is AuthState.LoggedIn -> when {
            a.claims?.isPending == true ->
                "Your access request is still being reviewed. You will get an email as soon as it is approved; then log in again."
            a.claims?.twoFactorSetupRequired == true ->
                "Set up two-factor authentication in the web portal (profile page), then log in again."
            else -> permissions?.missing(requiredPermissions)?.takeIf { it.isNotEmpty() }?.let {
                "Your account lacks the permission for $appName (${it.joinToString()}). Ask your ProGeo contact to grant it, then log in again."
            }
        }
    }
    val covered = auth is AuthState.LoggedOut || blockedBy != null
    var impressum by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // Hidden from accessibility services while covered, so TalkBack does not read it.
        Box(if (covered) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
        if (covered) {
            val context = LocalContext.current
            BackHandler { if (impressum) impressum = false else context.activity()?.finish() }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when {
                    impressum -> Box(Modifier.navigationBarsPadding()) {
                        ImpressumScreen(appName, appVersion, onBack = { impressum = false })
                    }
                    blockedBy != null -> AccessBlocked(backend, appName, blockedBy, (auth as? AuthState.LoggedIn)?.username) { impressum = true }
                    else -> LoginScreen(backend, appName, onImpressum = { impressum = true }, serverLabel = serverLabel)
                }
            }
        }
    }
}

@Composable
private fun AccessBlocked(backend: BackendClient, appName: String, message: String, username: String?, onImpressum: () -> Unit) {
    val scope = rememberCoroutineScope()
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BrandPanel {
            BrandHeader(appName, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            Text(if (username != null) "Hello $username," else "Hello,", style = MaterialTheme.typography.headlineSmall)
            Text(message)
            Button(
                onClick = { scope.launch { backend.logout() } },
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Log out") }
        }
        CompanyFooter()
        TextButton(onClick = onImpressum) { Text("Impressum") }
    }
}
