package com.bitzer.rmainspector.ui.signin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.ui.Connection
import com.bitzer.rmainspector.ui.SignInProgress
import com.bitzer.rmainspector.ui.components.BitzerTopBar
import com.bitzer.rmainspector.ui.components.ConnectionBanner
import com.bitzer.rmainspector.ui.components.InfoBanner
import com.bitzer.rmainspector.ui.components.ScreenHeader
import com.bitzer.rmainspector.ui.theme.BitzerColors

/** What the sign-in screen shows (spec §2). */
data class SignInState(
    val username: String = "",
    val password: String = "",
    val progress: SignInProgress = SignInProgress(),
    val serverUrl: String = "",
    val connection: Connection = Connection.Unknown,
    val appVersion: String = "",
)

object SignInTags {
    const val USERNAME = "signin_username"
    const val PASSWORD = "signin_password"
    const val SUBMIT = "signin_submit"
}

/** Sign-in (spec §2): the first screen until the user signs in. Holds only the typed text. */
@Composable
fun SignInScreen(
    initialUsername: String,
    progress: SignInProgress,
    serverUrl: String,
    connection: Connection,
    appVersion: String,
    onSignIn: (username: String, password: String) -> Unit,
    onEdited: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryServer: () -> Unit,
) {
    var username by rememberSaveable { mutableStateOf(initialUsername) }
    // The password stays in memory only (never saved to the instance state bundle or to disk).
    var password by remember { mutableStateOf("") }
    SignInContent(
        state = SignInState(username, password, progress, serverUrl, connection, appVersion),
        onUsernameChange = { username = it; onEdited() },
        onPasswordChange = { password = it; onEdited() },
        onSignIn = { onSignIn(username, password) },
        onOpenSettings = onOpenSettings,
        onRetryServer = onRetryServer,
    )
}

/** Stateless sign-in content — used by [SignInScreen] and by screenshot tests. */
@Composable
fun SignInContent(
    state: SignInState,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryServer: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = { keyboard?.hide(); onSignIn() }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val busy = state.progress.signingIn

    Scaffold(
        topBar = {
            BitzerTopBar(
                title = "BITZER Inspect",
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(vertical = 16.dp)) {
                ScreenHeader("Sign in", subtitle = "Use the username and password you were given for the RMA app server.")
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = onUsernameChange,
                        label = { Text("Username") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag(SignInTags.USERNAME),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = onPasswordChange,
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !busy,
                        isError = state.progress.error != null,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag(SignInTags.PASSWORD),
                    )
                    state.progress.error?.let {
                        InfoBanner(Icons.Filled.ErrorOutline, it, BitzerColors.Error)
                    }
                    Button(
                        onClick = submit,
                        enabled = !busy && state.username.isNotBlank() && state.password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(SignInTags.SUBMIT),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp, color = BitzerColors.Grey60)
                            Text("  Signing in…")
                        } else {
                            Text("Sign in")
                        }
                    }
                }

                ScreenHeader("Server")
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.serverUrl, style = MaterialTheme.typography.bodyLarge)
                    ConnectionBanner(state.connection) {
                        if (state.connection is Connection.Error) {
                            TextButton(onClick = onRetryServer, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("App version ${state.appVersion}", style = MaterialTheme.typography.bodyMedium,
                            color = BitzerColors.Grey90, modifier = Modifier.weight(1f))
                        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 56.dp)) { Text("Server settings") }
                    }
                }
            }
        }
    }
}
