package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgUserIdMutationError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationResult
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdMutationError
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdReplacementResult
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

class NativeGpgMutationFailureHandlingTest {
    @Test
    fun `user id mutations propagate cancellation`() {
        val revocationFailure = CancellationException("revocation cancelled")
        val actualRevocation =
            assertFailsWith<CancellationException> {
                NativeGpgUserIdRevocationService.revoke(
                    request = revocationRequest,
                    now = { throw revocationFailure },
                    waitForClock = { true },
                )
            }
        assertSame(revocationFailure, actualRevocation)

        val replacementFailure = CancellationException("replacement cancelled")
        val actualReplacement =
            assertFailsWith<CancellationException> {
                NativeGpgUserIdReplacementService.replace(
                    request = replacementRequest,
                    now = { throw replacementFailure },
                    waitForClock = { true },
                )
            }
        assertSame(replacementFailure, actualReplacement)
    }

    @Test
    fun `ordinary adapter failures map to internal failure`() {
        val revocation =
            NativeGpgUserIdRevocationService.revoke(
                request = revocationRequest,
                now = { error("clock unavailable") },
                waitForClock = { true },
            )
        assertEquals(
            GpgUserIdMutationError.InternalFailure,
            assertIs<GpgUserIdRevocationResult.Error>(revocation).reason,
        )

        val replacement =
            NativeGpgUserIdReplacementService.replace(
                request = replacementRequest,
                now = { error("clock unavailable") },
                waitForClock = { true },
            )
        assertEquals(
            GpgUserIdMutationError.InternalFailure,
            assertIs<GpgUserIdReplacementResult.Error>(replacement).reason,
        )
    }

    @Test
    fun `replacement reports empty private key before invalid user id`() {
        var clockReads = 0
        val result =
            NativeGpgUserIdReplacementService.replace(
                request =
                    replacementRequest.copy(
                        key = key.copy(privateKeyArmored = " \t"),
                        newUserId = " \t",
                    ),
                now = {
                    clockReads += 1
                    error("clock must not be read for an invalid replacement request")
                },
                waitForClock = { true },
            )
        assertEquals(
            GpgUserIdMutationError.EmptyPrivateKey,
            assertIs<GpgUserIdReplacementResult.Error>(result).reason,
        )
        assertEquals(0, clockReads)
    }

    @Test
    fun `replacement validates user id before clock and native candidate preselection`() {
        val invalidUserIds =
            listOf(
                " \t",
                "Alice\u0000",
                "Alice\u2028Injected",
                "Alice\u2029Injected",
                "\uD800",
                "\uDC00",
                "\uD800A",
                "A".repeat(1_025),
                "é".repeat(513),
            )

        invalidUserIds.forEach { newUserId ->
            var clockReads = 0
            val result =
                NativeGpgUserIdReplacementService.replace(
                    request =
                        replacementRequest.copy(
                            newUserId = newUserId,
                            candidateRevocationKeys =
                                listOf(GpgOpenPgpPublicKey("not a public key")),
                        ),
                    now = {
                        clockReads += 1
                        error("clock must not be read for an invalid replacement User ID")
                    },
                    waitForClock = { true },
                )
            assertEquals(
                GpgUserIdMutationError.InvalidNewUserId,
                assertIs<GpgUserIdReplacementResult.Error>(result).reason,
            )
            assertEquals(0, clockReads)
        }
    }

    @Test
    fun `retry waits until the exact timestamp and refreshes policy time`() {
        var clock = Instant.fromEpochMilliseconds(100_250)
        val waits = mutableListOf<Long>()
        val references = mutableListOf<Long>()
        val result = retryNativeTimeConflicts(
            now = { clock },
            waitForClock = { milliseconds ->
                waits += milliseconds
                clock = Instant.fromEpochMilliseconds(clock.toEpochMilliseconds() + milliseconds)
                true
            },
            retryAt = { attempt -> if (attempt == 1) 103L else null },
        ) {
            references += it
            references.size
        }
        assertEquals(2, result)
        assertEquals(listOf(2_750L), waits)
        assertEquals(listOf(100L, 103L), references)
    }

    @Test
    fun `retry rounds fractional milliseconds up to reach the signature timestamp`() {
        var clock = Instant.fromEpochSeconds(100, 999_999_999)
        val waits = mutableListOf<Long>()
        var attempts = 0
        retryNativeTimeConflicts(
            now = { clock },
            waitForClock = {
                waits += it
                clock = Instant.fromEpochSeconds(101)
                true
            },
            retryAt = { attempt -> if (attempt == 1) 101L else null },
        ) { ++attempts }
        assertEquals(listOf(1L), waits)
        assertEquals(2, attempts)
    }

    @Test
    fun `retry is bounded even when the clock does not advance`() {
        var attempts = 0
        var waits = 0
        val result = retryNativeTimeConflicts(
            now = { Instant.fromEpochSeconds(100) },
            waitForClock = { waits++; true },
            retryAt = { 101L },
        ) { ++attempts }
        assertEquals(6, result)
        assertEquals(5, waits)
    }

    @Test
    fun `distant conflicts and interrupted waits return without retrying`() {
        for (retryTime in listOf(101L, 106L)) {
            var attempts = 0
            var waits = 0
            retryNativeTimeConflicts(
                now = { Instant.fromEpochSeconds(100) },
                waitForClock = { waits++; false },
                retryAt = { retryTime },
            ) { ++attempts }
            assertEquals(1, attempts)
            assertEquals(if (retryTime == 101L) 1 else 0, waits)
        }
    }

    @Test
    fun `terminal time and policy conflicts do not retry`() {
        val terminalReasons = listOf(
            NativeOpenPgpUserIdMutationError.TIME_CONFLICT,
            NativeOpenPgpUserIdMutationError.POLICY_CONFLICT,
        )
        for (reason in terminalReasons) {
            var attempts = 0
            val result = retryNativeTimeConflicts(
                now = { Instant.fromEpochSeconds(100) },
                waitForClock = { error("terminal conflict must not wait") },
                retryAt = { result: NativeOpenPgpUserIdReplacementResult.Error -> result.retryAtEpochSeconds },
            ) {
                attempts++
                NativeOpenPgpUserIdReplacementResult.Error(reason)
            }
            assertEquals(reason, result.reason)
            assertEquals(1, attempts)
        }
        assertEquals(GpgUserIdMutationError.PolicyConflict, NativeOpenPgpUserIdMutationError.POLICY_CONFLICT.toDomain())
    }

    private companion object {
        val key =
            GpgKeyMaterial(
                privateKeyArmored = "private",
                publicKeyArmored = "public",
                fingerprint = "A".repeat(40),
                metadata = GpgAgentKeyMetadata(),
            )
        val revocationRequest =
            GpgUserIdRevocationRequest(
                key = key,
                identityId = "v1:${"B".repeat(64)}",
            )
        val replacementRequest =
            GpgUserIdReplacementRequest(
                key = key,
                oldIdentityId = "v1:${"B".repeat(64)}",
                newUserId = "New Identity <new@example.test>",
            )
    }
}
