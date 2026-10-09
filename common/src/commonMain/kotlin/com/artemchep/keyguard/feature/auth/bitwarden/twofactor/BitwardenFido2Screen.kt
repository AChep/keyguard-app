package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.feature.fido2.Fido2PromptEffect
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ColumnScope.LoginOtpScreenContentFido2WebAuthn(
    screenScope: LoginOtpScreenScope,
    state: BitwardenLoginTwofaState.Fido2WebAuthn,
) {
    Fido2PromptEffect(state.prompts)
    LoginOtpScreenContentFido2WebAuthnBrowser(screenScope, state)
    FlatItemLayoutExpressive(
        expressive = false,
        leading = {
            Checkbox(
                checked = state.rememberMe.checked,
                enabled = state.rememberMe.onChange != null,
                onCheckedChange = null,
            )
        },
        content = {
            Text(
                text = stringResource(Res.string.remember_me),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = state.rememberMe.onChange?.partially1(!state.rememberMe.checked),
    )
}
