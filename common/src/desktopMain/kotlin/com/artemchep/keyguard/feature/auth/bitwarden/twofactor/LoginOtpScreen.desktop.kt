package com.artemchep.keyguard.feature.auth.bitwarden.twofactor

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

@Composable
actual fun ColumnScope.LoginOtpScreenContentFido2WebAuthnWebView(
    screenScope: LoginOtpScreenScope,
    state: BitwardenLoginTwofaState.Fido2WebAuthn,
) {
    // Do nothing
}

@Composable
actual fun ColumnScope.LoginOtpScreenContentFido2WebAuthnBrowser(
    screenScope: LoginOtpScreenScope,
    state: BitwardenLoginTwofaState.Fido2WebAuthn,
) {
    // Do nothing
}
