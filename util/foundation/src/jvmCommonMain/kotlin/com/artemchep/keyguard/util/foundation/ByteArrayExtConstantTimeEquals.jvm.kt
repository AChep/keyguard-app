package com.artemchep.keyguard.util.foundation

import java.security.MessageDigest

actual fun ByteArray.constantTimeEquals(
    other: ByteArray,
): Boolean = MessageDigest.isEqual(this, other)
