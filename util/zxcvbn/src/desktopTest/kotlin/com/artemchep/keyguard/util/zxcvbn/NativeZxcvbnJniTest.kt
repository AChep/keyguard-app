package com.artemchep.keyguard.util.zxcvbn

import com.artemchep.keyguard.util.zxcvbn.bridge.NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT
import com.artemchep.keyguard.util.zxcvbn.bridge.NATIVE_ZXCVBN_JNI_FIELD_COUNT
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Null elements reach only the JNI bridge: the Kotlin API cannot express
 * them. The tests run under `-Xcheck:jni`, so they also prove that no JNI
 * call is made while an exception is pending.
 */
class NativeZxcvbnJniTest {
    @Test
    fun aNullUserInputBeatsAnOversizedPassword() {
        ensureZxcvbnAvailable()

        val status = NativeZxcvbnJni.estimate(
            password = OVERSIZED,
            userInputs = nullableArrayOf(null),
            out = LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT),
        )

        assertEquals(NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT, status)
    }

    @Test
    fun aNullUserInputBeatsAnEarlierOversizedOne() {
        ensureZxcvbnAvailable()

        val status = NativeZxcvbnJni.estimate(
            password = "password",
            userInputs = nullableArrayOf(OVERSIZED, null),
            out = LongArray(NATIVE_ZXCVBN_JNI_FIELD_COUNT),
        )

        assertEquals(NATIVE_ZXCVBN_BRIDGE_INVALID_ARGUMENT, status)
    }
}

/** Builds the `String[]` with null elements that Java code could pass. */
@Suppress("UNCHECKED_CAST")
private fun nullableArrayOf(vararg elements: String?): Array<String> =
    arrayOf(*elements) as Array<String>

/** Far over the limit, so the JNI bridge scans it in several chunks. */
private val OVERSIZED = "a".repeat(10_000)
