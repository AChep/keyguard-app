package com.artemchep.keyguard.util.foundation

expect fun ByteArray.constantTimeEquals(
    other: ByteArray,
): Boolean
