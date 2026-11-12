package com.progeo.core.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.progeo.core.UserFacingException
import com.progeo.core.auth.BackendClient
import com.progeo.core.auth.LoginCode
import com.progeo.core.auth.LoginResult
import com.progeo.core.qr.CodeScanDialog
import com.progeo.core.ui.BrandHeader
import com.progeo.core.ui.BrandPanel
import com.progeo.core.ui.CompanyFooter
import com.progeo.core.ui.MessageCard
import com.progeo.core.ui.theme.ProGeoColors
import kotlinx.coroutines.launch

/** State of the login screen; a ViewModel so a running login survives rotation. */
class LoginViewModel : ViewModel() {
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var otp by mutableStateOf("")
    var otpNeeded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var scanning by mutableStateOf(false)

    /** A scanned login code waiting for the two-factor code. */
    var code by mutableStateOf<LoginCode?>(null)
        private set

    val canSubmit: Boolean
        get() = !busy && (!otpNeeded || otp.isNotBlank()) && (code != null || (username.isNotBlank() && password.isNotEmpty()))

    fun submit(backend: BackendClient) {
        if (!canSubmit) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val c = code
                val result = if (c != null) backend.login(c, otp.takeIf { otpNeeded }) else backend.login(username, password, otp.takeIf { otpNeeded })
                when (result) {
                    LoginResult.Success -> {
                        password = ""
                        otp = ""
                        otpNeeded = false
                        code = null
                    }
                    LoginResult.OtpRequired -> {
                        otpNeeded = true
                        error = "Enter the code from your authenticator app."
                    }
                    LoginResult.OtpInvalid -> {
                        otp = ""
                        error = "The code is wrong or was already used. Try the next one."
                    }
                    is LoginResult.Failed -> {
                        error = result.message
                        // A refused login code cannot get better with a second factor.
                        if (c != null) cancelCode(keepError = true)
                    }
                }
            } catch (e: UserFacingException) {
                error = e.userMessage
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }

    fun scanned(backend: BackendClient, text: String) {
        scanning = false
        val c = LoginCode.parse(text)
        if (c == null) {
            error = "This QR code is not a ProGeo login code."
            return
        }
        code = c
        otp = ""
        otpNeeded = false
        submit(backend)
    }

    fun cancelCode(keepError: Boolean = false) {
        code = null
        otp = ""
        otpNeeded = false
        if (!keepError) error = null
    }
}

/**
 * The login of every ProGeo app, in the portal's design: user name and password (plus the
 * two-factor code when the account has 2FA), or a scanned login code.
 */
@Composable
fun LoginScreen(
    backend: BackendClient,
    appName: String,
    onImpressum: () -> Unit,
    serverLabel: String? = null,
    vm: LoginViewModel = viewModel(),
) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        BrandPanel {
            BrandHeader(appName, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            val c = vm.code
            if (c != null) {
                Text("Login code scanned", style = MaterialTheme.typography.headlineSmall)
                Text("Your account uses two-factor authentication. Enter the code from your authenticator app.")
                OtpField(vm, backend)
                vm.error?.let { MessageCard(it) }
                SubmitButton(vm, backend, "Continue")
                TextButton(onClick = { vm.cancelCode() }, enabled = !vm.busy) { Text("Cancel") }
            } else {
                Text("Sign in", style = MaterialTheme.typography.headlineSmall)
                Text("With your ProGeo portal account.")
                OutlinedTextField(
                    value = vm.username,
                    onValueChange = { vm.username = it },
                    label = { Text("User name or email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                var visible by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = vm.password,
                    onValueChange = { vm.password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (vm.otpNeeded) ImeAction.Next else ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { vm.submit(backend) }),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = if (visible) "Hide password" else "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (vm.otpNeeded) OtpField(vm, backend)
                vm.error?.let { MessageCard(it) }
                SubmitButton(vm, backend, "Log in")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = ProGeoColors.WarmGray2)
                    Text("or", style = MaterialTheme.typography.bodySmall, color = ProGeoColors.TextMuted, modifier = Modifier.padding(horizontal = 12.dp))
                    HorizontalDivider(Modifier.weight(1f), color = ProGeoColors.WarmGray2)
                }
                OutlinedButton(
                    onClick = { vm.scanning = true },
                    enabled = !vm.busy,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan login code")
                }
                Text(
                    "A login code is a QR code with a one-time login link of the ProGeo portal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProGeoColors.TextMuted,
                )
            }
        }
        CompanyFooter()
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onImpressum) { Text("Impressum") }
            serverLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ProGeoColors.TextMuted) }
        }
    }

    if (vm.scanning) {
        CodeScanDialog(
            onDismiss = { vm.scanning = false },
            onCode = { vm.scanned(backend, it) },
            title = "Scan login code",
            hint = "Hold the login QR code from the portal inside the frame.",
        )
    }
}

@Composable
private fun OtpField(vm: LoginViewModel, backend: BackendClient) {
    OutlinedTextField(
        value = vm.otp,
        onValueChange = { vm.otp = it.trim() },
        label = { Text("Two-factor code") },
        supportingText = { Text("From the authenticator app, or a recovery code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { vm.submit(backend) }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The navy pill button of the portal. */
@Composable
private fun SubmitButton(vm: LoginViewModel, backend: BackendClient, label: String) {
    Button(
        onClick = { vm.submit(backend) },
        enabled = vm.canSubmit,
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (vm.busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(label)
    }
}
