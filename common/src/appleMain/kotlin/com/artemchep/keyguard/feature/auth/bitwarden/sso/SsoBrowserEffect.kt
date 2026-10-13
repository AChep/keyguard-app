package com.artemchep.keyguard.feature.auth.bitwarden.sso

import androidx.compose.runtime.Composable

/** Native Apple login is hosted by SwiftUI and ASWebAuthenticationSession. */
@Composable
internal actual fun SsoBrowserEffect(prompt: SsoBrowserPrompt) = Unit
