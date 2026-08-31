package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.crypto.NativeGpgUserIdReplacementService
import com.artemchep.keyguard.crypto.NativeGpgUserIdRevocationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GpgUserIdMutationServiceCapabilityTest {
    @Test
    fun `native user id mutation services are supported`() {
        assertTrue(NativeGpgUserIdReplacementService.isSupported)
        assertTrue(NativeGpgUserIdRevocationService.isSupported)
    }

    @Test
    fun `unsupported replacement service reports unsupported platform`() {
        assertFalse(GpgUserIdReplacementServiceUnsupported.isSupported)

        val result = GpgUserIdReplacementServiceUnsupported.replace(replacementRequest)

        assertEquals(
            GpgUserIdReplacementError.UnsupportedPlatform,
            assertIs<GpgUserIdReplacementResult.Error>(result).reason,
        )
    }

    @Test
    fun `unsupported revocation service reports unsupported platform`() {
        assertFalse(GpgUserIdRevocationServiceUnsupported.isSupported)

        val result = GpgUserIdRevocationServiceUnsupported.revoke(revocationRequest)

        assertEquals(
            GpgUserIdRevocationError.UnsupportedPlatform,
            assertIs<GpgUserIdRevocationResult.Error>(result).reason,
        )
    }

    private companion object {
        val key =
            GpgKeyMaterial(
                privateKeyArmored = "private",
                publicKeyArmored = "public",
                fingerprint = "A".repeat(40),
                metadata = GpgAgentKeyMetadata(),
            )
        val replacementRequest =
            GpgUserIdReplacementRequest(
                key = key,
                oldIdentityId = "v1:${"B".repeat(64)}",
                newUserId = "New Identity <new@example.test>",
            )
        val revocationRequest =
            GpgUserIdRevocationRequest(
                key = key,
                identityId = "v1:${"B".repeat(64)}",
            )
    }
}
