package com.artemchep.keyguard.integration.wearcredentialproviderapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.NoCredentialException
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.launch

@Composable
fun WearCredentialProviderTestApp(
    credentialClient: WearCredentialClient,
) {
    val listState = rememberTransformingLazyColumnState()
    val scope = rememberCoroutineScope()
    var state by remember {
        mutableStateOf<CredentialRunState>(CredentialRunState.Idle)
    }

    AppScaffold {
        ScreenScaffold(
            scrollState = listState,
        ) { contentPadding ->
            TransformingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = contentPadding,
            ) {
                item("header") {
                    ListHeader {
                        Text(
                            modifier = Modifier.fillMaxWidth(),
                            text = "Credential test app",
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                item("summary") {
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        text = "Standalone Wear OS consumer used to validate password and passkey retrieval from the Keyguard credential provider.",
                    )
                }

                items(
                    items = CredentialRequestPreset.entries,
                    key = { it.name },
                    contentType = { "credential_request_preset" },
                ) { preset ->
                    val isLoading = state is CredentialRunState.Loading
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        onClick = {
                            state = CredentialRunState.Loading(preset)
                            scope.launch {
                                state = runCatching {
                                    credentialClient.getCredential(preset)
                                }.fold(
                                    onSuccess = { result ->
                                        CredentialRunState.Success(
                                            preset = preset,
                                            result = result,
                                        )
                                    },
                                    onFailure = { error ->
                                        CredentialRunState.Error(
                                            preset = preset,
                                            errorName = error.displayName(),
                                            message = error.displayMessage(),
                                        )
                                    },
                                )
                            }
                        },
                    ) {
                        Text(preset.label)
                    }
                }

                resultSection(
                    state = state,
                    onClear = {
                        state = CredentialRunState.Idle
                    },
                )
            }
        }
    }
}

private fun Throwable.displayName(): String = when (this) {
    is NoCredentialException -> "NoCredentialException"
    else -> this::class.simpleName ?: "Credential error"
}

private fun Throwable.displayMessage(): String = when (this) {
    is NoCredentialException -> message ?: "No credential was available for the selected preset."
    else -> message.orEmpty()
}

private fun TransformingLazyColumnScope.resultSection(
    state: CredentialRunState,
    onClear: () -> Unit,
) {
    val title = when (state) {
        CredentialRunState.Idle -> "Ready"
        is CredentialRunState.Loading -> "Running ${state.preset.label}"
        is CredentialRunState.Success -> "Success"
        is CredentialRunState.Error -> "Error"
    }

    item("result-header") {
        ListSubHeader(
            label = {
                Text(title)
            },
        )
    }

    when (state) {
        CredentialRunState.Idle -> {
            item("result-idle") {
                BodyText("Choose one of the fixed flows to open Credential Manager.")
            }
        }

        is CredentialRunState.Loading -> {
            item("result-loading") {
                BodyText("Waiting for Credential Manager to return a credential or an error.")
            }
        }

        is CredentialRunState.Success -> {
            item("result-preset") {
                KeyValueRow(
                    key = "Preset",
                    value = state.preset.label,
                )
            }
            item("result-credential-type") {
                KeyValueRow(
                    key = "Credential type",
                    value = state.result.credentialType,
                )
            }
            item("result-headline") {
                BodyText(
                    value = state.result.headline,
                    emphasize = true,
                )
            }
            state.result.fields.forEach { (key, value) ->
                item("result-field-$key") {
                    KeyValueRow(
                        key = key,
                        value = value,
                    )
                }
            }
            state.result.rawJson?.takeIf { it.isNotBlank() }?.let { rawJson ->
                item("result-raw-json") {
                    BodyText(
                        value = rawJson,
                        monospace = true,
                    )
                }
            }
        }

        is CredentialRunState.Error -> {
            item("result-error-preset") {
                KeyValueRow(
                    key = "Preset",
                    value = state.preset.label,
                )
            }
            item("result-error-name") {
                KeyValueRow(
                    key = "Exception",
                    value = state.errorName,
                )
            }
            item("result-error-message") {
                KeyValueRow(
                    key = "Message",
                    value = state.message.ifBlank { "No message provided." },
                )
            }
        }
    }

    if (state !is CredentialRunState.Idle) {
        item("result-clear") {
            FilledTonalButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onClear,
            ) {
                Text("Clear result")
            }
        }
    }
}

@Composable
private fun BodyText(
    value: String,
    emphasize: Boolean = false,
    monospace: Boolean = false,
) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        text = value,
        fontWeight = if (emphasize) FontWeight.SemiBold else null,
        fontFamily = if (monospace) FontFamily.Monospace else null,
    )
}

@Composable
private fun KeyValueRow(
    key: String,
    value: String,
) {
    BodyText(
        value = key,
        emphasize = true,
    )
    BodyText(
        value = value,
        monospace = true,
    )
}

data class CredentialResult(
    val credentialType: String,
    val headline: String,
    val fields: List<Pair<String, String>>,
    val rawJson: String?,
)

sealed interface CredentialRunState {
    data object Idle : CredentialRunState

    data class Loading(
        val preset: CredentialRequestPreset,
    ) : CredentialRunState

    data class Success(
        val preset: CredentialRequestPreset,
        val result: CredentialResult,
    ) : CredentialRunState

    data class Error(
        val preset: CredentialRequestPreset,
        val errorName: String,
        val message: String,
    ) : CredentialRunState
}

enum class CredentialRequestPreset(
    val label: String,
    val includesPassword: Boolean,
    val passkeyRpId: String?,
) {
    PASSWORD_ONLY(
        label = "Password only",
        includesPassword = true,
        passkeyRpId = null,
    ),
    PASSKEY_EXAMPLE(
        label = "Passkey: example.com",
        includesPassword = false,
        passkeyRpId = "example.com",
    ),
    PASSKEY_ACCOUNTS_EXAMPLE(
        label = "Passkey: accounts.example.com",
        includesPassword = false,
        passkeyRpId = "accounts.example.com",
    ),
    PASSWORD_AND_PASSKEY(
        label = "Password + Passkey",
        includesPassword = true,
        passkeyRpId = "example.com",
    ),
}
