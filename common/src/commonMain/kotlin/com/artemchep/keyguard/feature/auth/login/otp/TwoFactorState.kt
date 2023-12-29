package com.artemchep.keyguard.feature.auth.login.otp

import kotlinx.coroutines.flow.StateFlow

data class TwoFactorState(
    val providers: List<TwoFactorProviderItem> = emptyList(),
    val loadingState: StateFlow<Boolean>,
    val state: LoginTwofaState,
)
