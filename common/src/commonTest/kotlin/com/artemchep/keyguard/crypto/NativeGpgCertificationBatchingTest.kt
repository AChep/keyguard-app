package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpCertificationAuthority
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.nativecrypto.NativeCryptoErrorCode
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeGpgCertificationBatchingTest {
    @Test
    fun `63 distinct authorities are coalesced into one request`() {
        val coalesced = authorities(63).coalesceCertificationAuthorities()

        assertEquals(63, coalesced.size)
    }

    @Test
    fun `duplicate revisions split across the old boundary are coalesced into one request`() {
        val firstRevision = authority(index = 0, revision = "revision-b")
        val secondRevision = authority(index = 0, revision = "revision-a")
        val input =
            buildList {
                add(firstRevision)
                addAll(authorities(62, startIndex = 1))
                add(secondRevision)
            }

        val coalesced = input.coalesceCertificationAuthorities()

        assertEquals(63, coalesced.size)
        assertEquals(
            "revision-a\nrevision-b",
            coalesced
                .single { it.primaryFingerprint == fingerprint(0) }
                .publicKey.armored,
        )
    }

    @Test
    fun `authority preparation is deterministic and order independent`() {
        val input =
            listOf(
                authority(index = 2, revision = "revision-c"),
                authority(index = 0, revision = "revision-b"),
                authority(index = 1, revision = "revision-d"),
                authority(index = 0, revision = "revision-a"),
                authority(index = 2, revision = "revision-c"),
            )

        assertEquals(
            input.coalesceCertificationAuthorities(),
            input.reversed().coalesceCertificationAuthorities(),
        )
        assertEquals(
            listOf(fingerprint(0), fingerprint(1), fingerprint(2)),
            input.coalesceCertificationAuthorities().map { it.primaryFingerprint },
        )
    }

    @Test
    fun `64 distinct authorities fail closed`() {
        val failure =
            assertFailsWith<NativeCryptoException> {
                authorities(64).coalesceCertificationAuthorities()
            }

        assertEquals("open_pgp_user_id_certification_evaluate", failure.operation)
        assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
    }

    @Test
    fun `empty authority list stays empty`() {
        assertEquals(
            emptyList(),
            emptyList<GpgOpenPgpCertificationAuthority>().coalesceCertificationAuthorities(),
        )
    }

    private fun authorities(
        count: Int,
        startIndex: Int = 0,
    ): List<GpgOpenPgpCertificationAuthority> =
        List(count) { offset ->
            authority(index = startIndex + offset)
        }

    private fun authority(
        index: Int,
        revision: String = "revision-$index",
    ) = GpgOpenPgpCertificationAuthority(
        publicKey = GpgOpenPgpPublicKey(revision),
        primaryFingerprint = fingerprint(index),
    )

    private fun fingerprint(index: Int): String =
        index
            .toString(16)
            .uppercase()
            .padStart(length = 40, padChar = '0')
}
