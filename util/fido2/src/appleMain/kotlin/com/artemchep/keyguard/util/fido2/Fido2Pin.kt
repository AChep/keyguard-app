@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package com.artemchep.keyguard.util.fido2

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.precomposedStringWithCanonicalMapping

internal actual fun normalizeFido2Pin(pin: String): String =
    NSString.create(string = pin).precomposedStringWithCanonicalMapping
