package com.artemchep.keyguard.common.service.crypto

import com.artemchep.keyguard.crypto.GpgRenewalPolicyJvm
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class GpgRenewalPolicyJvmTest {
    @Test
    fun `weak RSA digests are upgraded while constrained algorithms preserve them`() {
        val policy = policy(now = Instant.fromEpochSeconds(10))

        listOf(HashAlgorithmTags.SHA1, HashAlgorithmTags.RIPEMD160).forEach { weakHash ->
            assertEquals(
                HashAlgorithmTags.SHA256,
                policy.replacementHashAlgorithm(PublicKeyAlgorithmTags.RSA_GENERAL, weakHash),
            )
            listOf(
                PublicKeyAlgorithmTags.DSA,
                PublicKeyAlgorithmTags.ECDSA,
                PublicKeyAlgorithmTags.EDDSA_LEGACY,
            ).forEach { algorithm ->
                assertEquals(
                    weakHash,
                    policy.replacementHashAlgorithm(algorithm, weakHash),
                )
            }
        }
    }

    @Test
    fun `equal timestamp waits for a strictly newer OpenPGP second`() {
        var now = Instant.fromEpochSeconds(10)
        var waits = 0
        val policy = GpgRenewalPolicyJvm(
            now = { now },
            waitForClock = { milliseconds ->
                waits += 1
                now += milliseconds.milliseconds
            },
        )

        assertEquals(
            Date(11_000L),
            policy.replacementSignatureCreationTime(Date(10_000L)),
        )
        assertEquals(1, waits)
    }

    @Test
    fun `time conflict is returned after five clock waits`() {
        var waits = 0
        val policy = GpgRenewalPolicyJvm(
            now = { Instant.fromEpochSeconds(10) },
            waitForClock = { waits += 1 },
        )

        assertNull(policy.replacementSignatureCreationTime(Date(10_000L)))
        assertEquals(5, waits)
    }

    @Test
    fun `signature expiration keeps its absolute time when creation advances`() {
        val policy = policy(now = Instant.fromEpochSeconds(10))

        assertEquals(
            90L,
            policy.replacementSignatureExpirationDuration(
                templateCreationTime = Date(10_000L),
                templateDurationSeconds = 100L,
                replacementCreationTime = Date(20_000L),
            ),
        )
    }

    @Test
    fun `already expired signatures retain the shortest nonzero duration`() {
        val policy = policy(now = Instant.fromEpochSeconds(10))

        assertEquals(
            1L,
            policy.replacementSignatureExpirationDuration(
                templateCreationTime = Date(10_000L),
                templateDurationSeconds = 5L,
                replacementCreationTime = Date(20_000L),
            ),
        )
    }

    @Test
    fun `zero and exact unsigned wrap leave copied expiration untouched`() {
        val policy = policy(now = Instant.fromEpochSeconds(10))

        assertNull(
            policy.replacementSignatureExpirationDuration(
                templateCreationTime = Date(10_000L),
                templateDurationSeconds = 0L,
                replacementCreationTime = Date(20_000L),
            ),
        )
        assertNull(
            policy.replacementSignatureExpirationDuration(
                templateCreationTime = Date((UInt.MAX_VALUE.toLong() - 9L) * 1_000L),
                templateDurationSeconds = 10L,
                replacementCreationTime = Date(20_000L),
            ),
        )
    }

    private fun policy(
        now: Instant,
    ) = GpgRenewalPolicyJvm(
        now = { now },
        waitForClock = {},
    )
}
