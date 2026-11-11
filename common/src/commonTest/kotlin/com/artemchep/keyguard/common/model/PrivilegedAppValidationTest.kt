package com.artemchep.keyguard.common.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivilegedAppValidationTest {
    private val fingerprint = List(32) { "AB" }.joinToString(separator = ":")

    @Test
    fun packageNameAndFingerprintOfTheListAreAccepted() {
        assertTrue(isPrivilegedAppValid("com.android.chrome", fingerprint))
        assertTrue(isPrivilegedAppValid("org.mozilla.fennec_aurora", fingerprint))
        assertTrue(isPrivilegedAppValid("com.Example.Browser2", "0F:" + fingerprint.drop(3)))
        assertTrue(isPrivilegedAppValid("android", fingerprint))
    }

    @Test
    fun malformedPackageNameIsRejected() {
        listOf(
            "",
            " ",
            "com.example browser",
            "com..example",
            ".com.example",
            "com.example.",
            "com.1example",
            "com.example/browser",
            "com.example.browser\n",
            "a".repeat(256),
        ).forEach { packageName ->
            assertFalse(isPrivilegedAppValid(packageName, fingerprint), packageName)
        }
    }

    @Test
    fun fingerprintInAnotherFormIsRejected() {
        listOf(
            "",
            fingerprint.lowercase(),
            fingerprint.replace(":", ""),
            fingerprint.dropLast(3),
            "$fingerprint:AB",
            fingerprint.replaceFirst("AB", "GZ"),
            " $fingerprint",
        ).forEach { value ->
            assertFalse(isPrivilegedAppValid("com.android.chrome", value), value)
        }
    }
}
