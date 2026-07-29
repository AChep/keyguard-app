package com.artemchep.keyguard.feature.auth.userverification

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.biometric.BiometricPromptEffect
import com.artemchep.keyguard.feature.keyguard.unlock.UnlockScreenContainer
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.yubikey.YubiKeyPromptEffect
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.OtherScaffold
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.KeyguardYubiKey
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Long enough for the field to be attached before it is asked for focus; only ever
 * used when there is no hardware method, so the keyboard does not fight a prompt.
 */
private const val AUTO_FOCUS_DELAY_MS = 80L

/**
 * The in-place "prove you are present" gate.
 *
 * Mounted as a node by whoever owns the surface, so it renders as the whole screen
 * with no cancel of its own — the host's own chrome supplies the way out.
 */
class UserVerificationRoute(
    private val onAuthenticated: () -> Unit,
) : Route {
    @Composable
    override fun Content(
    ) {
        UserVerificationScreen(
            onAuthenticated = onAuthenticated,
        )
    }
}

@Composable
fun UserVerificationScreen(
    onAuthenticated: () -> Unit,
) {
    val state = produceUserVerificationState(
        onAuthenticated = onAuthenticated,
    )
    val content = state.content.getOrNull()
        ?: return

    BiometricPromptEffect(content.sideEffects.showBiometricPromptFlow)
    YubiKeyPromptEffect(content.sideEffects.showYubiKeyPromptFlow)
    OtherScaffold {
        UnlockScreenContainer(
            top = {
                UserVerificationHeader()
            },
            center = {
                UserVerificationPasswordField(
                    content = content,
                )
            },
            bottom = {
                UserVerificationActions(
                    content = content,
                )
            },
        )
    }
}

@Composable
private fun UserVerificationHeader() {
    Text(
        textAlign = TextAlign.Center,
        text = stringResource(Res.string.userverification_header_text),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun UserVerificationPasswordField(
    content: UserVerificationState.Content,
) {
    val requester = remember {
        FocusRequester2()
    }
    val keyboardOnGo: (KeyboardActionScope.() -> Unit)? =
        if (content.onVerify != null) {
            // lambda
            {
                content.onVerify.invoke()
            }
        } else {
            null
        }
    PasswordFlatTextField(
        modifier = Modifier
            .focusRequester2(requester),
        testTag = "field:password",
        value = content.password,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = keyboardOnGo,
        ),
    )
    LaunchedEffect(requester) {
        delay(AUTO_FOCUS_DELAY_MS)
        if (content.biometric == null && content.yubiKey == null) {
            requester.requestFocus()
        }
    }
}

@Composable
private fun ColumnScope.UserVerificationActions(
    content: UserVerificationState.Content,
) {
    val onVerifyClick by rememberUpdatedState(
        content.onVerify,
    )
    Button(
        modifier = Modifier
            .testTag("btn:go")
            .fillMaxWidth(),
        enabled = content.onVerify != null,
        onClick = {
            onVerifyClick?.invoke()
        },
    ) {
        Text(
            text = stringResource(Res.string.userverification_button_go),
        )
    }
    UserVerificationHardwareButtons(
        content = content,
    )
}

@Composable
private fun ColumnScope.UserVerificationHardwareButtons(
    content: UserVerificationState.Content,
) {
    ExpandedIfNotEmpty(
        modifier = Modifier
            .align(Alignment.CenterHorizontally),
        valueOrNull = Unit.takeIf {
            content.biometric != null || content.yubiKey != null
        },
    ) {
        Row(
            modifier = Modifier
                .padding(top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (content.biometric != null) {
                UserVerificationHardwareButton(
                    onClick = content.biometric.onClick,
                    imageVector = Icons.Outlined.Fingerprint,
                )
            }
            if (content.yubiKey != null) {
                UserVerificationHardwareButton(
                    onClick = content.yubiKey.onClick,
                    imageVector = Icons.Outlined.KeyguardYubiKey,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserVerificationHardwareButton(
    onClick: (() -> Unit)?,
    imageVector: ImageVector,
) {
    val updatedOnClick by rememberUpdatedState(onClick)
    Button(
        enabled = onClick != null,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.outlinedButtonColors(),
        elevation = null,
        border = ButtonDefaults.outlinedButtonBorder(
            enabled = onClick != null,
        ),
        onClick = {
            updatedOnClick?.invoke()
        },
        contentPadding = PaddingValues(16.dp),
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
        )
    }
}
