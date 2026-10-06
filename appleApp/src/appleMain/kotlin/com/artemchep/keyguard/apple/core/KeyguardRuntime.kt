package com.artemchep.keyguard.apple.core

/** The app owns background work; an AutoFill core lives for one presented request. */
enum class KeyguardRuntime {
    APP,
    AUTOFILL,
}
