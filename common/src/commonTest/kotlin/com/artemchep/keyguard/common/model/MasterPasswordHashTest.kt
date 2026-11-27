package com.artemchep.keyguard.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MasterPasswordHashTest {
    @Test
    fun `hashes are equal when kdf and bytes match`() {
        val a = MasterPasswordHash(
            kdf = MasterKdf.Pbkdf2Legacy,
            byteArray = byteArrayOf(1, 2, 3),
        )
        val b = MasterPasswordHash(
            kdf = MasterKdf.Pbkdf2Legacy,
            byteArray = byteArrayOf(1, 2, 3),
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashes are not equal when kdfs differ`() {
        val a = MasterPasswordHash(
            kdf = MasterKdf.Pbkdf2Legacy,
            byteArray = byteArrayOf(1, 2, 3),
        )
        val b = MasterPasswordHash(
            kdf = MasterKdf.Argon2id.V1,
            byteArray = byteArrayOf(1, 2, 3),
        )

        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashes are not equal when bytes differ`() {
        val a = MasterPasswordHash(
            kdf = MasterKdf.Argon2id.V1,
            byteArray = byteArrayOf(1, 2, 3),
        )
        val b = MasterPasswordHash(
            kdf = MasterKdf.Argon2id.V1,
            byteArray = byteArrayOf(1, 2, 4),
        )

        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }
}
