package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.nativecrypto.NativeCryptoOpenPgp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeBridgeUtilsTest {
    @Test
    fun `OpenPGP key list clamp preserves the first documents`() {
        val limit = NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST
        val documents = List(limit + 2) { index -> index }

        val clamped = documents.clampToNativeOpenPgpKeyLimit()

        assertEquals(limit, clamped.size)
        assertEquals((0 until limit).toList(), clamped)
    }

    @Test
    fun `OpenPGP key list clamp preserves lists within the limit`() {
        val documents = listOf(1, 2, 3)

        assertEquals(documents, documents.clampToNativeOpenPgpKeyLimit())
    }

    @Test
    fun `revocation selection ignores unrelated candidates beyond the native limit`() {
        val required = authority(1, "A")
        val selection = RevocationCandidateSelection<String>(setOf(required))

        repeat(NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST + 1) { index ->
            selection.consider("unrelated-$index", setOf(authority(1, "B$index")))
        }
        selection.consider("matching", setOf(required))

        assertEquals(listOf("matching"), selection.result())
    }

    @Test
    fun `revocation selection matches the full component identity`() {
        val required = authority(22, "SUBKEY")
        val selection = RevocationCandidateSelection<String>(setOf(required))

        selection.consider("wrong-algorithm", setOf(authority(1, "SUBKEY")))
        selection.consider(
            "matching-subkey",
            setOf(authority(1, "PRIMARY"), authority(22, "SUBKEY")),
        )
        selection.consider("already-covered-authority", setOf(required))

        assertEquals(listOf("matching-subkey"), selection.result())
    }

    @Test
    fun `revocation selection retains the native limit for relevant candidates`() {
        val authorities = List(
            NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST + 1,
        ) { index -> authority(1, "F$index") }
        val selection = RevocationCandidateSelection<Int>(authorities.toSet())

        val failure = assertFailsWith<NativeCryptoException> {
            authorities.forEachIndexed { index, authority ->
                selection.consider(index, setOf(authority))
            }
        }
        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
        assertEquals("open_pgp_revocation_candidates", failure.operation)
    }

    @Test
    fun `revocation selection retains unknown candidates and fails closed`() {
        val selection = RevocationCandidateSelection<Int>(setOf(authority(1, "A")))

        val failure = assertFailsWith<NativeCryptoException> {
            repeat(NativeCryptoOpenPgp.MAX_KEY_DOCUMENTS_PER_REQUEST + 1) { index ->
                selection.consider(index, componentAuthorities = null)
            }
        }

        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
    }

    private fun authority(
        algorithm: Int,
        fingerprint: String,
    ) = RevocationAuthorityId(
        publicKeyAlgorithmId = algorithm,
        fingerprint = fingerprint,
    )
}
