package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.model.GpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpPublicKey
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementResult
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationError
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationRequest
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationResult
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.nativecrypto.NativeOpenPgpUserIdReplacementError
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
            GpgUserIdRevocationError.InternalFailure,
            assertIs<GpgUserIdRevocationResult.Error>(revocation).reason,
        )

        val replacement =
            NativeGpgUserIdReplacementService.replace(
                request = replacementRequest,
                now = { error("clock unavailable") },
                waitForClock = { true },
            )
        assertEquals(
            GpgUserIdReplacementError.InternalFailure,
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
            GpgUserIdReplacementError.EmptyPrivateKey,
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
                GpgUserIdReplacementError.InvalidNewUserId,
                assertIs<GpgUserIdReplacementResult.Error>(result).reason,
            )
            assertEquals(0, clockReads)
        }
    }

    @Test
    fun `time conflict retry is bounded and stops when waiting fails`() {
        var attempts = 0
        var waits = 0
        val exhausted =
            retryNativeTimeConflicts(
                now = { Instant.fromEpochSeconds(attempts.toLong()) },
                waitForClock = {
                    waits += 1
                    true
                },
                isTimeConflict = { true },
            ) {
                attempts += 1
                attempts
            }
        assertEquals(6, exhausted)
        assertEquals(6, attempts)
        assertEquals(5, waits)

        attempts = 0
        waits = 0
        val interrupted =
            retryNativeTimeConflicts(
                now = { Instant.fromEpochSeconds(attempts.toLong()) },
                waitForClock = {
                    waits += 1
                    false
                },
                isTimeConflict = { true },
            ) {
                attempts += 1
                attempts
            }
        assertEquals(1, interrupted)
        assertEquals(1, attempts)
        assertEquals(1, waits)
    }

    @Test
    fun `time conflict retry captures one fresh reference time per attempt`() {
        var clockReads = 0
        val referenceTimes = mutableListOf<Long>()

        val result =
            retryNativeTimeConflicts(
                now = {
                    clockReads += 1
                    Instant.fromEpochSeconds(100L + clockReads)
                },
                waitForClock = { true },
                isTimeConflict = { attempt -> attempt < 3 },
            ) { referenceTimeEpochSeconds ->
                referenceTimes += referenceTimeEpochSeconds
                referenceTimes.size
            }

        assertEquals(3, result)
        assertEquals(3, clockReads)
        assertEquals(listOf(101L, 102L, 103L), referenceTimes)
    }

    @Test
    fun `replacement retries clock conflicts but not policy conflicts`() {
        var attempts = 0
        var waits = 0
        val recovered =
            retryNativeTimeConflicts(
                now = { Instant.fromEpochSeconds(attempts.toLong()) },
                waitForClock = {
                    waits += 1
                    true
                },
                isTimeConflict = { result ->
                    result is NativeOpenPgpUserIdReplacementResult.Error &&
                        result.reason == NativeOpenPgpUserIdReplacementError.TIME_CONFLICT
                },
            ) {
                attempts += 1
                NativeOpenPgpUserIdReplacementResult.Error(
                    if (attempts == 1) {
                        NativeOpenPgpUserIdReplacementError.TIME_CONFLICT
                    } else {
                        NativeOpenPgpUserIdReplacementError.TARGET_INACTIVE
                    },
                )
            }
        assertEquals(
            NativeOpenPgpUserIdReplacementError.TARGET_INACTIVE,
            recovered.reason,
        )
        assertEquals(2, attempts)
        assertEquals(1, waits)

        attempts = 0
        waits = 0
        val permanent =
            retryNativeTimeConflicts(
                now = { Instant.fromEpochSeconds(attempts.toLong()) },
                waitForClock = {
                    waits += 1
                    true
                },
                isTimeConflict = { result ->
                    result is NativeOpenPgpUserIdReplacementResult.Error &&
                        result.reason == NativeOpenPgpUserIdReplacementError.TIME_CONFLICT
                },
            ) {
                attempts += 1
                NativeOpenPgpUserIdReplacementResult.Error(
                    NativeOpenPgpUserIdReplacementError.POLICY_CONFLICT,
                )
            }
        assertEquals(NativeOpenPgpUserIdReplacementError.POLICY_CONFLICT, permanent.reason)
        assertEquals(1, attempts)
        assertEquals(0, waits)
        assertEquals(
            GpgUserIdReplacementError.PolicyConflict,
            permanent.reason.toDomain(),
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
