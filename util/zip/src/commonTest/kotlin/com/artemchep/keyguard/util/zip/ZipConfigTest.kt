package com.artemchep.keyguard.util.zip

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ZipConfigTest {
    @Test
    fun rejectsAnEmptyEncryptionPassword() {
        assertFailsWith<IllegalArgumentException> {
            ZipConfig.Encryption(password = "")
        }
    }
}
