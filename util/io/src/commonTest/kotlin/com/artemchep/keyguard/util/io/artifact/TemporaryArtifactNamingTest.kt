package com.artemchep.keyguard.util.io.artifact

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemporaryArtifactNamingTest {
    @Test
    fun rolesUseTheCanonicalCompactContract() {
        val nonce = "123e4567-e89b-42d3-a456-426614174000"
        assertEquals(
            ".kg-tmp-v1u-n-$nonce.tmp",
            temporaryArtifactName(TemporaryArtifactRole.New, nonce),
        )
        assertEquals(
            ".kg-tmp-v1u-o-$nonce.tmp",
            temporaryArtifactName(TemporaryArtifactRole.Previous, nonce),
        )
        assertEquals(
            ".kg-tmp-v1u-s-$nonce.tmp",
            temporaryArtifactName(TemporaryArtifactRole.Scratch, nonce),
        )
    }

    @Test
    fun predicateRecognizesOnlyTheCanonicalPrefix() {
        assertTrue(
            isReservedTemporaryArtifactName(
                ".kg-tmp-n-123e4567-e89b-42d3-a456-426614174000.tmp",
            ),
        )
        assertFalse(isReservedTemporaryArtifactName("document.kgtmp"))
        assertFalse(isReservedTemporaryArtifactName("keyguard-private-id.tmp"))
    }

    @Test
    fun parserRecognizesVersionOneLeaseProtocols() {
        val nonce = "123e4567-e89b-42d3-a456-426614174000"
        assertEquals(
            TemporaryArtifactName(
                protocol = TemporaryArtifactProtocol.FileLeaseV1,
                role = TemporaryArtifactRole.New,
                nonce = nonce,
                entryKind = TemporaryArtifactEntryKind.Data,
            ),
            parseTemporaryArtifactName(".kg-tmp-v1f-n-$nonce.tmp"),
        )
        assertEquals(
            TemporaryArtifactName(
                protocol = TemporaryArtifactProtocol.DirectoryLeaseV1,
                role = TemporaryArtifactRole.Previous,
                nonce = nonce,
                entryKind = TemporaryArtifactEntryKind.Data,
            ),
            parseTemporaryArtifactName(".kg-tmp-v1d-o-$nonce.tmp"),
        )
        assertEquals(
            TemporaryArtifactName(
                protocol = TemporaryArtifactProtocol.SidecarLeaseV1,
                role = TemporaryArtifactRole.Scratch,
                nonce = nonce,
                entryKind = TemporaryArtifactEntryKind.Data,
            ),
            parseTemporaryArtifactName(".kg-tmp-v1s-s-$nonce.tmp"),
        )
        assertEquals(
            TemporaryArtifactName(
                protocol = TemporaryArtifactProtocol.SidecarLeaseV1,
                role = TemporaryArtifactRole.Scratch,
                nonce = nonce,
                entryKind = TemporaryArtifactEntryKind.Lease,
            ),
            parseTemporaryArtifactName(".kg-tmp-v1s-s-$nonce.lease"),
        )
    }

    @Test
    fun malformedAndUnknownNamesStayReservedButDoNotParse() {
        for (name in listOf(
            ".kg-tmp-n-123e4567-e89b-42d3-a456-426614174000.tmp",
            ".kg-tmp-v1u-n-123e4567-e89b-42d3-a456-426614174000.tmp",
            ".kg-tmp-v2d-n-123e4567-e89b-42d3-a456-426614174000.tmp",
            ".kg-tmp-v2s-n-123e4567-e89b-42d3-a456-426614174000.tmp",
            ".kg-tmp-v3-n-123e4567-e89b-42d3-a456-426614174000.tmp",
            ".kg-tmp-v1d-n-123e4567-e89b-42d3-a456-426614174000.lease",
            ".kg-tmp-v1s-x-123e4567-e89b-42d3-a456-426614174000.tmp",
            ".kg-tmp-v1s-n-123e4567-e89b-32d3-a456-426614174000.tmp",
        )) {
            assertTrue(isReservedTemporaryArtifactName(name), name)
            assertEquals(null, parseTemporaryArtifactName(name), name)
        }
    }

    @Test
    fun nameConstructionRejectsNonV4AndNonRfcVariantUuids() {
        assertFailsWith<IllegalArgumentException> {
            temporaryArtifactName(
                TemporaryArtifactRole.New,
                "123e4567-e89b-32d3-a456-426614174000",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            temporaryArtifactName(
                TemporaryArtifactRole.New,
                "123e4567-e89b-42d3-7456-426614174000",
            )
        }
    }
}
