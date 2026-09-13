package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("TooManyFunctions")
class AgentApprovalWindowMemoryTest {
    @Test
    fun `completed policy update is visible to the next cache access`() = runTest {
        val vaultSession = createVaultSession()
        val approvalCacheConfig = createApprovalCacheConfig()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(
            approvalCacheConfig = approvalCacheConfig,
            getVaultSession = FixedVaultSession(vaultSession),
            scope = backgroundScope,
        )
        runCurrent()
        val session = memory.getOrGenerateSession(vaultSession)

        session.access { "key" }.remember()
        val remembered = session.access { "key" }
        assertTrue(remembered.isRemembered)
        assertTrue(remembered.canReuseNow())

        approvalCacheConfig.updateCachePolicy(
            cachePolicy = TestPolicy.Connection,
            persist = {},
        )()

        // Do not run the test scheduler here: a completed write must be visible
        // synchronously, without waiting for a policy collector to run.
        assertFalse(remembered.canReuseNow())
        var observedPolicy: TestPolicy? = null
        val access = session.access { policy ->
            observedPolicy = policy
            "key"
        }
        assertEquals(TestPolicy.Connection, observedPolicy)
        assertFalse(access.isRemembered)
    }

    @Test
    fun `policy away and back transition invalidates an old approval`() = runTest {
        val vaultSession = createVaultSession()
        val approvalCacheConfig = createApprovalCacheConfig()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(
            approvalCacheConfig = approvalCacheConfig,
            getVaultSession = FixedVaultSession(vaultSession),
            scope = backgroundScope,
        )
        runCurrent()
        val session = memory.getOrGenerateSession(vaultSession)

        session.access { "key" }.remember()
        val remembered = session.access { "key" }
        assertTrue(remembered.canReuseNow())

        approvalCacheConfig.updateCachePolicy(
            cachePolicy = TestPolicy.Connection,
            persist = {},
        )()
        approvalCacheConfig.updateCachePolicy(
            cachePolicy = TestPolicy.Application,
            persist = {},
        )()

        // The final policy equals the original policy, but its revision does
        // not. No collector gets a chance to conflate the intermediate value.
        assertFalse(remembered.canReuseNow())
        assertFalse(session.access { "key" }.isRemembered)
    }

    @Test
    fun `stale access cannot remember after a policy away and back transition`() = runTest {
        val vaultSession = createVaultSession()
        val approvalCacheConfig = createApprovalCacheConfig()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(
            approvalCacheConfig = approvalCacheConfig,
            getVaultSession = FixedVaultSession(vaultSession),
            scope = backgroundScope,
        )
        runCurrent()
        val session = memory.getOrGenerateSession(vaultSession)
        val staleAccess = session.access { "key" }

        approvalCacheConfig.updateCachePolicy(
            cachePolicy = TestPolicy.Connection,
            persist = {},
        )()
        approvalCacheConfig.updateCachePolicy(
            cachePolicy = TestPolicy.Application,
            persist = {},
        )()
        // Observe the new revision before the old approval prompt completes.
        assertFalse(session.access { "key" }.isRemembered)

        staleAccess.remember()

        assertFalse(session.access { "key" }.isRemembered)
    }

    @Test
    fun `approval window away and back transition invalidates an old approval`() = runTest {
        val vaultSession = createVaultSession()
        val approvalCacheConfig = createApprovalCacheConfig()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(
            approvalCacheConfig = approvalCacheConfig,
            getVaultSession = FixedVaultSession(vaultSession),
            scope = backgroundScope,
        )
        runCurrent()
        val session = memory.getOrGenerateSession(vaultSession)

        session.access { "key" }.remember()
        val remembered = session.access { "key" }
        assertTrue(remembered.canReuseNow())

        approvalCacheConfig.updateApprovalWindow(
            approvalWindow = Duration.ZERO,
            persist = {},
        )()
        assertFalse(remembered.canReuseNow())
        approvalCacheConfig.updateApprovalWindow(
            approvalWindow = Duration.INFINITE,
            persist = {},
        )()

        // The final window equals the original one. Its newer revision must
        // still invalidate the approval without a scheduler drain.
        assertFalse(remembered.canReuseNow())
        assertFalse(session.access { "key" }.isRemembered)
    }

    @Test
    fun `shared config invalidates every approval memory instance`() = runTest {
        val vaultSession = createVaultSession()
        val approvalCacheConfig = createApprovalCacheConfig()
        val firstMemory = AgentApprovalWindowMemory<String, TestPolicy>(
            approvalCacheConfig = approvalCacheConfig,
            getVaultSession = FixedVaultSession(vaultSession),
            scope = backgroundScope,
        )
        val secondMemory = AgentApprovalWindowMemory<String, TestPolicy>(
            approvalCacheConfig = approvalCacheConfig,
            getVaultSession = FixedVaultSession(vaultSession),
            scope = backgroundScope,
        )
        runCurrent()
        val firstSession = firstMemory.getOrGenerateSession(vaultSession)
        val secondSession = secondMemory.getOrGenerateSession(vaultSession)

        firstSession.access { "key" }.remember()
        secondSession.access { "key" }.remember()
        assertTrue(firstSession.access { "key" }.isRemembered)
        assertTrue(secondSession.access { "key" }.isRemembered)

        approvalCacheConfig.updateCachePolicy(
            cachePolicy = TestPolicy.Connection,
            persist = {},
        )()

        assertFalse(firstSession.access { "key" }.isRemembered)
        assertFalse(secondSession.access { "key" }.isRemembered)
    }

    @Test
    fun `infinite approvals evict the least recently used entry at the size cap`() = runTest {
        val vaultSession = createVaultSession()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(
            approvalCacheConfig = createApprovalCacheConfig(),
            getVaultSession = FixedVaultSession(vaultSession),
            scope = backgroundScope,
            maxCacheEntries = 2,
        )
        runCurrent()
        val session = memory.getOrGenerateSession(vaultSession)

        session.access { "a" }.remember()
        session.access { "b" }.remember()
        val rememberedB = session.access { "b" }
        assertTrue(session.access { "a" }.isRemembered)

        session.access { "c" }.remember()

        assertFalse(rememberedB.canReuseNow())
        assertTrue(session.access { "a" }.isRemembered)
        assertFalse(session.access { "b" }.isRemembered)
        assertTrue(session.access { "c" }.isRemembered)
    }

    @Test
    fun `insertion prunes expired entries before applying the size cap`() = runTest {
        val vaultSession = createVaultSession()
        val timeSource = TestTimeSource()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(
            approvalCacheConfig = createApprovalCacheConfig(
                approvalWindow = 1.minutes,
            ),
            getVaultSession = FixedVaultSession(vaultSession),
            scope = backgroundScope,
            maxCacheEntries = 2,
            timeSource = timeSource,
        )
        runCurrent()
        val session = memory.getOrGenerateSession(vaultSession)

        session.access { "expired" }.remember()
        val remembered = session.access { "expired" }
        timeSource += 30.seconds
        assertTrue(remembered.canReuseNow())
        session.access { "live" }.remember()
        // Make the older entry most recently used without extending its expiry.
        assertTrue(session.access { "expired" }.isRemembered)
        timeSource += 31.seconds
        assertFalse(remembered.canReuseNow(), "Reuse checks must not extend expiry")

        session.access { "new" }.remember()

        assertTrue(session.access { "live" }.isRemembered)
        assertFalse(session.access { "expired" }.isRemembered)
        assertTrue(session.access { "new" }.isRemembered)
    }

    @Test
    fun `reuse checks fail immediately during configuration and memory contention`() = runTest {
        val vault = createVaultSession()
        val config = createApprovalCacheConfig()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(config, FixedVaultSession(vault), backgroundScope)
        val session = memory.getOrGenerateSession(vault)
        session.access { "key" }.remember()
        val remembered = session.access { "key" }
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val update = async {
            config.updateApprovalWindow(Duration.INFINITE, persist = {
                started.complete(Unit)
                finish.await()
            })()
        }
        started.await()
        assertFalse(remembered.canReuseNow(), "Configuration is locked, even though its value is unchanged")
        val otherAccess = async { session.access { "other" } }
        runCurrent()
        assertFalse(otherAccess.isCompleted)
        assertFalse(remembered.canReuseNow(), "Approval memory is also locked")
        finish.complete(Unit)
        update.await()
        otherAccess.await()
        assertTrue(remembered.canReuseNow(), "A completed no-op update preserves the grant")
    }

    @Test
    fun `reuse checks reject failed persistence without another cache access`() = runTest {
        val vault = createVaultSession()
        val config = createApprovalCacheConfig()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(config, FixedVaultSession(vault), backgroundScope)
        val session = memory.getOrGenerateSession(vault)
        session.access { "key" }.remember()
        val remembered = session.access { "key" }
        assertFailsWith<IllegalStateException> {
            config.updateCachePolicy(TestPolicy.Connection, persist = { error("Persistence failed") })()
        }
        assertFalse(remembered.canReuseNow())
    }

    @Test
    fun `reuse checks preserve misses and reject cleared or replaced sessions`() = runTest {
        val vault = createVaultSession()
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(
            createApprovalCacheConfig(), FixedVaultSession(vault), backgroundScope,
        )
        val session = memory.getOrGenerateSession(vault)
        val miss = session.access { "key" }
        miss.remember()
        assertFalse(miss.canReuseNow(), "A later insertion must not change the original cache miss")
        val remembered = session.access { "key" }
        assertTrue(remembered.canReuseNow())
        memory.clearSession()
        assertFalse(remembered.canReuseNow())
        val nextSession = memory.getOrGenerateSession(vault)
        nextSession.access { "key" }.remember()
        val nextRemembered = nextSession.access { "key" }
        assertTrue(nextRemembered.canReuseNow())
        memory.getOrGenerateSession(createVaultSession())
        assertFalse(nextRemembered.canReuseNow())
    }

    @Test
    fun `providers without an immediate snapshot cannot authorize reuse`() = runTest {
        val vault = createVaultSession()
        val config = AgentApprovalCacheConfigProvider {
            AgentApprovalCacheConfig(Duration.INFINITE, TestPolicy.Application, 0L)
        }
        val memory = AgentApprovalWindowMemory<String, TestPolicy>(config, FixedVaultSession(vault), backgroundScope)
        val session = memory.getOrGenerateSession(vault)
        session.access { "key" }.remember()
        val remembered = session.access { "key" }
        assertTrue(remembered.isRemembered)
        assertFalse(remembered.canReuseNow())
    }

    private enum class TestPolicy {
        Application,
        Connection,
    }

    private fun createApprovalCacheConfig(
        approvalWindow: Duration = Duration.INFINITE,
        cachePolicy: TestPolicy = TestPolicy.Application,
    ) = AgentApprovalCacheConfigState(
        loadApprovalWindow = { approvalWindow },
        loadCachePolicy = { cachePolicy },
    )

    private class FixedVaultSession(
        private val session: MasterSession.Key,
    ) : GetVaultSession {
        override val valueOrNull: MasterSession = session

        override fun invoke(): Flow<MasterSession> = flowOf(session)
    }

    private fun createVaultSession() = MasterSession.Key(
        masterKey = MasterKey(
            version = MasterKdfVersion.LATEST,
            byteArray = byteArrayOf(1, 2, 3),
        ),
        di = DI {},
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    )
}
