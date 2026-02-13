package com.artemchep.keyguard.common.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MasterPasswordHashTest {
    @Test
    fun `hashes are equal when version and bytes match`() {
        val a = MasterPasswordHash(
            version = MasterKdfVersion.V0,
            byteArray = byteArrayOf(1, 2, 3),
        )
        val b = MasterPasswordHash(
            version = MasterKdfVersion.V0,
            byteArray = byteArrayOf(1, 2, 3),
        )

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashes are not equal when versions differ`() {
        val a = MasterPasswordHash(
            version = MasterKdfVersion.V0,
            byteArray = byteArrayOf(1, 2, 3),
        )
        val b = MasterPasswordHash(
            version = MasterKdfVersion.V1,
            byteArray = byteArrayOf(1, 2, 3),
        )

        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `hashes are not equal when bytes differ`() {
        val a = MasterPasswordHash(
            version = MasterKdfVersion.LATEST,
            byteArray = byteArrayOf(1, 2, 3),
        )
        val b = MasterPasswordHash(
            version = MasterKdfVersion.LATEST,
            byteArray = byteArrayOf(1, 2, 4),
        )

        assertNotEquals(a, b)
        assertNotEquals(a.hashCode(), b.hashCode())
    }
}
