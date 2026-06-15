package com.artemchep.keyguard.feature.auth.companion

internal class CompanionAuthSessionUnavailableException : IllegalStateException(
    "Companion auth request is no longer active. Please start again.",
)
