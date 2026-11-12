package com.progeo.luaflasher.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.progeo.luaflasher.AppGraph
import com.progeo.luaflasher.backend.LoginResult
import com.progeo.luaflasher.core.FlasherException

/** What the login form holds; kept in a ViewModel so it survives rotation. */
class LoginFormState {
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var otp by mutableStateOf("")
    var otpNeeded by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    val canSubmit: Boolean
        get() = !busy && username.isNotBlank() && password.isNotEmpty() && (!otpNeeded || otp.isNotEmpty())

    /** Logs in; true on success. Asks for the two-factor code when the server wants one. */
    suspend fun submit(graph: AppGraph): Boolean {
        busy = true
        error = null
        try {
            when (val r = graph.backend.login(username, password, otp.takeIf { otpNeeded })) {
                LoginResult.Success -> {
                    password = ""
                    otp = ""
                    otpNeeded = false
                    return true
                }
                LoginResult.OtpRequired -> {
                    otpNeeded = true
                    error = "Enter the code from your authenticator app."
                }
                LoginResult.OtpInvalid -> {
                    otp = ""
                    error = "The code is wrong or was already used. Try the next one."
                }
                is LoginResult.Failed -> error = r.message
            }
        } catch (e: FlasherException) {
            error = e.userMessage
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            busy = false
        }
        return false
    }
}

@Composable
fun LoginForm(
    state: LoginFormState,
    onSubmit: () -> Unit,
    hint: String = "Your account needs the device permissions and access to the project's location.",
) {
    Section("ProGeo login") {
        Text(hint, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.username,
            onValueChange = { state.username = it },
            label = { Text("User name or email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = { state.password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (state.otpNeeded) {
            OutlinedTextField(
                value = state.otp,
                onValueChange = { state.otp = it.trim() },
                label = { Text("Two-factor code") },
                supportingText = { Text("From the authenticator app, or a recovery code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let { MessageCard(it) }
        Button(onClick = onSubmit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "Logging in…" else "Log in")
        }
    }
}
