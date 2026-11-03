package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.agent.AgentApprovalCachePolicy
import com.artemchep.keyguard.common.service.agent.ApprovalCacheInvalidation
import com.artemchep.keyguard.common.service.agent.finishAfterBlockedAgentRead
import com.artemchep.keyguard.common.service.agent.finishAfterBlockedApprovalCacheAccess
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture as Fixture
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.KEYGRIP
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.OTHER_KEYGRIP
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.caller
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.cipherFilter
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.createVaultSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@Suppress("TooManyFunctions")
@OptIn(ExperimentalCoroutinesApi::class)
class GpgAgentApprovalReuseTest {
    @Test
    fun `sign and decrypt reuse macos terminal approvals across new processes`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            listOf<Byte?>(40, null).forEach { application ->
                val fixture = Fixture(this)
                fixture.succeed(operation, caller(connection = 1, application = application))
                fixture.succeed(operation, caller(connection = 2, application = application))
                assertEquals(1, fixture.approvals, operation.toString())
                assertEquals(2, fixture.crypto.calls)
            }
        }
    }

    @Test
    fun `sign and decrypt share different terminal sessions only at application scope`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            listOf(AgentApprovalCachePolicy.Default, AgentApprovalCachePolicy.Application).forEach { policy ->
                val fixture = Fixture(this, policy)
                fixture.succeed(operation, caller(connection = 1, terminal = 30))
                fixture.succeed(operation, caller(connection = 2, terminal = 31))
                fixture.succeed(operation, caller(connection = 3, terminal = 32, application = 41))
                val expected = if (policy == AgentApprovalCachePolicy.Application) 2 else 3
                assertEquals(expected, fixture.approvals, operation.toString() + policy)
            }
        }
    }

    @Test
    fun `failed joint proof keeps sign and decrypt approvals scoped to the process`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            listOf(AgentApprovalCachePolicy.Default, AgentApprovalCachePolicy.Application).forEach { policy ->
                val fixture = Fixture(this, policy)
                fixture.succeed(operation, caller(connection = 1, terminal = null, application = null))
                fixture.succeed(operation, caller(connection = 2, terminal = null, application = null))
                assertEquals(2, fixture.approvals, operation.toString() + policy)
            }
        }
    }

    @Test
    fun `approval reuse stays separate for operations keys and authorization contexts`() = runTest {
        val fixture = Fixture(this)
        var expected = 0
        GpgAgentOperation.entries.forEach { operation ->
            fixture.succeed(operation, caller(connection = 1, context = 50))
            fixture.succeed(operation, caller(connection = 2, context = 50))
            assertEquals(++expected, fixture.approvals, operation.toString())

            fixture.succeed(operation, caller(connection = 3, context = 50), OTHER_KEYGRIP)
            assertEquals(++expected, fixture.approvals, "Different keygrip")

            fixture.succeed(operation, caller(connection = 4, context = 51))
            assertEquals(++expected, fixture.approvals, "Different authorization context")
        }
    }

    @Test
    fun `denied and failed operations do not create remembered approvals`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.onApproval = { false }
            assertIs<GpgAgentOperationResult.UserDenied>(fixture.request(operation, caller(connection = 1)))
            assertEquals(1, fixture.approvals)
            assertEquals(0, fixture.crypto.calls)

            fixture.onApproval = { true }
            fixture.crypto.fail = true
            assertIs<GpgAgentOperationResult.Failure>(fixture.request(operation, caller(connection = 2)))
            assertEquals(2, fixture.approvals)

            fixture.crypto.fail = false
            fixture.succeed(operation, caller(connection = 3))
            fixture.succeed(operation, caller(connection = 4))
            assertEquals(3, fixture.approvals)
            assertEquals(3, fixture.crypto.calls)
        }
    }

    @Test
    fun `config changes vault lock and a new vault session invalidate both operation grants`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.succeed(operation, caller(connection = 1))
            fixture.succeed(operation, caller(connection = 2))
            assertEquals(1, fixture.approvals)

            fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Application, persist = {})()
            fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Default, persist = {})()
            fixture.succeed(operation, caller(connection = 3))
            assertEquals(2, fixture.approvals)

            fixture.config.updateApprovalWindow(Duration.ZERO, persist = {})()
            fixture.config.updateApprovalWindow(Duration.INFINITE, persist = {})()
            fixture.succeed(operation, caller(connection = 4))
            assertEquals(3, fixture.approvals)

            val unlock = fixture.lock()
            runCurrent()
            unlock()
            fixture.succeed(operation, caller(connection = 5))
            assertEquals(4, fixture.approvals)

            fixture.vault.valueOrNull = createVaultSession()
            fixture.succeed(operation, caller(connection = 6))
            assertEquals(5, fixture.approvals)
        }
    }

    @Test
    fun `an approval open across a config change cannot restore an old grant`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val result = fixture.finishPendingApproval(operation) {
                fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Application, persist = {})()
                fixture.config.updateCachePolicy(AgentApprovalCachePolicy.Default, persist = {})()
            }
            assertIs<GpgAgentOperationResult.Success<*>>(result)

            fixture.succeed(operation, caller(connection = 2))
            assertEquals(2, fixture.approvals)
        }
    }

    @Test
    fun `lock or replacement vault session during approval prevents either private operation`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (replaceSession in listOf(false, true)) {
                val fixture = Fixture(this)
                val result = fixture.finishPendingApproval(operation) {
                    fixture.vault.valueOrNull = if (replaceSession) createVaultSession() else MasterSession.Empty()
                }
                assertIs<GpgAgentOperationResult.VaultLocked>(result)
                assertEquals(0, fixture.crypto.calls)
                val event = fixture.pendingHistory.items.single()
                assertEquals(GpgUsageHistoryResponseType.VAULT_LOCKED.name, event.responseType)
                assertEquals(KEYGRIP, event.cipherId)
                assertEquals(KEYGRIP, event.fingerprint)
                assertEquals(KEYGRIP, event.keygrip)
            }
        }
    }

    @Test
    fun `keys removed deleted archived stripped or replaced during approval are not used`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val changes = listOf(
                "removed", "deleted", "archived", "private removed", "replaced item", "replaced account",
            )
            for (change in changes) {
                val fixture = Fixture(this)
                val result = fixture.finishPendingApproval(operation) {
                    fixture.ciphers.value = fixture.ciphers.value.mapNotNull { cipher ->
                        if (cipher.id != KEYGRIP) return@mapNotNull cipher
                        when (change) {
                            "removed" -> null
                            "deleted" -> cipher.copy(deletedDate = Instant.parse("2024-01-02T00:00:00Z"))
                            "archived" -> cipher.copy(archivedDate = Instant.parse("2024-01-02T00:00:00Z"))
                            "private removed" -> cipher.copy(gpgKey = cipher.gpgKey?.copy(privateKeyArmored = null))
                            "replaced item" -> cipher.copy(id = "replacement")
                            else -> cipher.copy(accountId = "replacement")
                        }
                    }
                }
                assertIs<GpgAgentOperationResult.KeyNotFound>(result)
                assertEquals(0, fixture.crypto.calls, change)
            }
        }
    }

    @Test
    fun `archiving a GPG key prevents sign and decrypt despite a remembered approval`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val caller = caller(connection = 1)
            fixture.succeed(operation, caller)
            fixture.ciphers.value = fixture.ciphers.value.map { cipher ->
                cipher.copy(archivedDate = Instant.parse("2024-01-02T00:00:00Z"))
            }

            assertIs<GpgAgentOperationResult.KeyNotFound>(fixture.request(operation, caller))
            assertEquals(1, fixture.crypto.calls)
            assertEquals(1, fixture.approvals)
        }
    }

    @Test
    fun `filter changed during approval is read before sign or decrypt`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val result = fixture.finishPendingApproval(operation) {
                fixture.filter.value = cipherFilter(OTHER_KEYGRIP)
            }
            assertIs<GpgAgentOperationResult.KeyNotFound>(result)
            assertEquals(0, fixture.crypto.calls)
        }
    }

    @Test
    fun `revocation during signing approval prevents the signature`() = runTest {
        val fixture = Fixture(this)
        val result = fixture.finishPendingApproval(GpgAgentOperation.SIGN) { fixture.revokedCipherIds += KEYGRIP }
        assertIs<GpgAgentOperationResult.KeyNotFound>(result)
        assertEquals(0, fixture.crypto.calls)
    }

    @Test
    fun `locked-origin request can unlock and then receive explicit approval`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val unlock = fixture.lock()
            val result = fixture.finishPendingApproval(operation, unlock)
            assertIs<GpgAgentOperationResult.Success<*>>(result)
            assertEquals(1, fixture.crypto.calls)
            assertEquals(1, fixture.approvals)
        }
    }

    @Test
    fun `cached and locked-origin operations resolve each key only once`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.succeed(operation, caller(connection = 1))
            assertEquals(4, fixture.resolutions, "Explicit approval refreshes both keys")
            fixture.resolutions = 0
            fixture.succeed(operation, caller(connection = 2))
            assertEquals(2, fixture.resolutions, "Cached approval resolves each key once")
            assertEquals(1, fixture.approvals)

            val unlock = fixture.lock()
            fixture.resolutions = 0
            val result = fixture.finishPendingApproval(operation, unlock)
            assertIs<GpgAgentOperationResult.Success<*>>(result)
            assertEquals(2, fixture.resolutions, "Unlock approval resolves each key once")
            assertEquals(2, fixture.approvals)
        }
    }

    @Test
    fun `cached approvals read key and filter changes after settings contention`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (removeKey in listOf(false, true)) {
                val fixture = Fixture(this)
                fixture.succeed(operation, caller(connection = 1))
                val result = finishAfterBlockedApprovalCacheAccess(
                    config = fixture.config,
                    request = { fixture.request(operation, caller(connection = 2)) },
                ) {
                    if (removeKey) {
                        fixture.ciphers.value = fixture.ciphers.value.filter { it.id != KEYGRIP }
                    } else {
                        fixture.filter.value = cipherFilter(OTHER_KEYGRIP)
                    }
                }
                assertIs<GpgAgentOperationResult.KeyNotFound>(result)
                assertEquals(1, fixture.approvals)
                assertEquals(1, fixture.crypto.calls)
            }
        }
    }

    @Test
    fun `vault denial history falls back to the cipher fingerprint`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            fixture.ciphers.value = fixture.ciphers.value.map { cipher ->
                cipher.copy(gpgKey = cipher.gpgKey?.let { gpgKey ->
                    gpgKey.copy(metadata = gpgKey.metadata?.copy(
                        certificates = gpgKey.metadata.certificates.map { certificate ->
                            certificate.copy(components = certificate.components.map { component ->
                                component.copy(fingerprint = "")
                            })
                        },
                    ))
                })
            }
            val result = fixture.finishPendingApproval(operation) {
                fixture.vault.valueOrNull = MasterSession.Empty()
            }
            assertIs<GpgAgentOperationResult.VaultLocked>(result)
            val event = fixture.pendingHistory.items.single()
            assertEquals(GpgUsageHistoryResponseType.VAULT_LOCKED.name, event.responseType)
            assertEquals(KEYGRIP, event.fingerprint)
            assertEquals(0, fixture.crypto.calls)
        }
    }

    @Test
    fun `cached operations require new approval after settings change during eligibility reads`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (readFilter in listOf(false, true)) {
                for (change in ApprovalCacheInvalidation.entries) {
                    val fixture = Fixture(this)
                    val caller = caller(connection = 1)
                    fixture.succeed(operation, caller)
                    val result = finishAfterBlockedAgentRead(
                        beforeRead = { hook ->
                            if (readFilter) fixture.beforeFilterRead = hook else fixture.beforeCipherRead = hook
                        },
                        request = { fixture.request(operation, caller) },
                    ) { change.apply(fixture.config) }

                    assertIs<GpgAgentOperationResult.Success<*>>(result)
                    assertEquals(2, fixture.approvals, "$operation, filter=$readFilter, $change")
                    assertEquals(2, fixture.crypto.calls)
                    fixture.succeed(operation, caller)
                    val expected = if (change == ApprovalCacheInvalidation.Disable) 3 else 2
                    assertEquals(expected, fixture.approvals, "New approval must use the current policy")
                }
            }
        }
    }

    @Test
    fun `renewed approvals still reject denial and removed keys`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (deny in listOf(false, true)) {
                val fixture = Fixture(this)
                fixture.succeed(operation, caller(connection = 1))
                fixture.onApproval = {
                    fixture.ciphers.value = emptyList()
                    !deny
                }
                val result = finishAfterBlockedAgentRead(
                    beforeRead = { fixture.beforeCipherRead = it },
                    request = { fixture.request(operation, caller(connection = 1)) },
                ) { ApprovalCacheInvalidation.Policy.apply(fixture.config) }
                if (deny) {
                    assertIs<GpgAgentOperationResult.UserDenied>(result)
                } else {
                    assertIs<GpgAgentOperationResult.KeyNotFound>(result)
                }
                assertEquals(2, fixture.approvals)
                assertEquals(1, fixture.crypto.calls)
            }
        }
    }

    @Test
    fun `reordering duplicate GPG keys retains the approved cipher`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val original = fixture.ciphers.value.first()
            fixture.ciphers.value = listOf(original, original.copy(id = "duplicate"))
            val result = fixture.finishPendingApproval(operation) {
                fixture.ciphers.value = fixture.ciphers.value.reversed()
            }
            assertIs<GpgAgentOperationResult.Success<*>>(result)
            assertEquals(1, fixture.crypto.calls)
            assertEquals(original.id, fixture.prompts.single().cipherId)
            assertEquals(original.id, fixture.history.single().cipherId)
        }
    }
}
