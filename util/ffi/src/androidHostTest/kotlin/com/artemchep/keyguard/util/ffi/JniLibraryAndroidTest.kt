package com.artemchep.keyguard.util.ffi

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class JniLibraryAndroidTest {
    @Test
    fun missingSystemLibraryIsUnavailable() {
        val library = JniLibrary(
            name = "keyguard_ffi_test_jni",
            pathProperty = "keyguard.nativeFfiTest.libraryPath",
            unavailable = { cause -> IllegalStateException("unavailable", cause) },
        )

        val error = assertFailsWith<IllegalStateException> { library.ensureLoaded() }
        assertIs<UnsatisfiedLinkError>(error.cause)
    }
}
