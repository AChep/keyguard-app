package com.artemchep.keyguard.util.zxcvbn.bridge

import kotlin.test.Test
import kotlin.test.assertEquals

class NativeZxcvbnStrictStringTest {
    /**
     * A password that cannot be encoded as UTF-8 is rejected before the C
     * bridge, which validates its input with `str::from_utf8`.
     *
     * The rejection is reported as the ABI's own invalid-argument scalar
     * rather than as a thrown exception, so this bridge and the JNI bridge
     * answer identically for identical input. Throwing here would bypass the
     * caller's decode path and surface a different exception type on Apple
     * targets only.
     */
    @Test
    fun malformedUtf16PasswordIsRejectedBeforeTheCBridge() {
        val result = NativeZxcvbn.estimate(
            password = "\uD800",
            userInputs = emptyList(),
            out = LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT),
        )

        assertEquals(NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT, result)

        val decoded = decodeNativeZxcvbnFailure(result)
        assertEquals(NativeZxcvbnFailureKind.InvalidInput, decoded.kind)
        assertEquals(NATIVE_ZXCVBN_BRIDGE_CODE_INVALID_ARGUMENT, decoded.bridgeCode)
    }

    /** A malformed user input is rejected the same way as a password. */
    @Test
    fun malformedUtf16UserInputIsRejectedBeforeTheCBridge() {
        val result = NativeZxcvbn.estimate(
            password = "password",
            userInputs = listOf("\uD800"),
            out = LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT),
        )

        assertEquals(NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT, result)
    }
}
