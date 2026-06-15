package com.artemchep.keyguard.provider.bitwarden.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthApiTest {
    @Test
    fun `argon2 memory converts from mb to kb`() {
        assertEquals(65_536, argon2MemoryKb(64))
        assertEquals(Int.MAX_VALUE - 1023, argon2MemoryKb(2_097_151))
    }

    @Test
    fun `argon2 memory rejects int overflow`() {
        assertFailsWith<IllegalArgumentException> {
            argon2MemoryKb(2_097_152)
        }
        assertFailsWith<IllegalArgumentException> {
            argon2MemoryKb(4_194_305)
        }
    }
}
