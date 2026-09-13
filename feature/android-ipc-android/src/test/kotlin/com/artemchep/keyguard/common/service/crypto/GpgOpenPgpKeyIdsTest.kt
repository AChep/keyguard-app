package com.artemchep.keyguard.common.service.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgOpenPgpKeyIdsTest {
    @Test
    fun `fingerprint key id uses the unsigned low 64 bits`() {
        assertEquals(
            0x0123_4567_89AB_CDEF,
            fingerprintToKeyId("00112233445566778899AABB0123456789ABCDEF"),
        )
        assertEquals(
            -1L,
            fingerprintToKeyId("FFFFFFFFFFFFFFFF"),
        )
    }

    @Test
    fun `subkey id resolution returns owner primary and rejects collisions`() {
        val first = Candidate("first", setOf(1L, 11L))
        val second = Candidate("second", setOf(2L, 22L))

        assertEquals(
            listOf(first, second),
            resolveUniqueOpenPgpKeyIds(
                keyIds = listOf(11L, 2L),
                candidates = listOf(first, second),
                candidateKeyIds = Candidate::ids,
            ),
        )
        assertNull(
            resolveUniqueOpenPgpKeyIds(
                keyIds = listOf(11L),
                candidates = listOf(first, first.copy(name = "collision")),
                candidateKeyIds = Candidate::ids,
            ),
        )
        assertNull(
            resolveUniqueOpenPgpKeyIds(
                keyIds = listOf(99L),
                candidates = listOf(first, second),
                candidateKeyIds = Candidate::ids,
            ),
        )
    }

    @Test
    fun `selected rings reject primary or subkey collisions with stored rings`() {
        val first = Candidate("first", setOf(1L, 11L))
        val second = Candidate("second", setOf(2L, 22L))
        val colliding = Candidate("collision", setOf(3L, 11L))

        assertFalse(
            hasOpenPgpKeyIdCollision(
                selected = listOf(first, second),
                candidates = listOf(first, second),
                candidateKeyIds = Candidate::ids,
            ),
        )
        assertTrue(
            hasOpenPgpKeyIdCollision(
                selected = listOf(first),
                candidates = listOf(first, second, colliding),
                candidateKeyIds = Candidate::ids,
            ),
        )
    }

    private data class Candidate(
        val name: String,
        val ids: Set<Long>,
    )
}
