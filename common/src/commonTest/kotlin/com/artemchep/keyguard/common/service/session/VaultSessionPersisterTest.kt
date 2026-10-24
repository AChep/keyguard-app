package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.PersistedSession
import com.artemchep.keyguard.common.service.vault.KeyReadWriteRepository
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class VaultSessionPersisterTest {
    private val masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1))

    private val unlocked = MasterSession.Key(
        masterKey = masterKey,
        session = object : VaultSession {
            override val id = "vault"
            override val active = MutableStateFlow(true)
            override fun retire() = Unit
            override fun close() = Unit
        },
        origin = MasterSession.Key.Authenticated,
        createdAt = Instant.fromEpochSeconds(0),
    )

    private val sessionFlow = MutableStateFlow<MasterSession>(unlocked)
    private val persistFlow = MutableStateFlow(true)
    private val writes = mutableListOf<PersistedSession?>()
    private var failWrites = false

    private val persister = VaultSessionPersister(
        getVaultSession = object : GetVaultSession {
            override val valueOrNull get() = sessionFlow.value
            override fun invoke() = sessionFlow
        },
        getVaultPersist = object : GetVaultPersist {
            override fun invoke() = persistFlow
        },
        keyReadWriteRepository = object : KeyReadWriteRepository {
            override fun get() = emptyFlow<PersistedSession?>()

            override fun put(session: PersistedSession?): IO<Unit> = {
                writes += session
                if (failWrites) error("write failed")
            }
        },
    )

    @Test
    fun `unlocked and enabled writes the master key`() = runTest {
        val before = Clock.System.now()
        backgroundScope.launch { persister.run() }
        runCurrent()
        val after = Clock.System.now()

        val written = assertNotNull(writes.single())
        assertSame(masterKey, written.masterKey)
        assertTrue(written.createdAt in before..after)
        assertTrue(written.persistedAt in before..after)
    }

    @Test
    fun `unlocked and disabled clears the persisted session`() = runTest {
        persistFlow.value = false
        backgroundScope.launch { persister.run() }
        runCurrent()

        assertEquals(listOf<PersistedSession?>(null), writes)
    }

    @Test
    fun `locked clears the persisted session`() = runTest {
        sessionFlow.value = MasterSession.Empty()
        backgroundScope.launch { persister.run() }
        runCurrent()

        assertEquals(listOf<PersistedSession?>(null), writes)
    }

    @Test
    fun `every change is written including repeated values`() = runTest {
        backgroundScope.launch { persister.run() }
        runCurrent()
        persistFlow.value = false
        runCurrent()
        sessionFlow.value = MasterSession.Empty()
        runCurrent()
        persistFlow.value = true
        runCurrent()

        assertEquals(4, writes.size)
        assertSame(masterKey, writes[0]?.masterKey)
        assertEquals(listOf(null, null, null), writes.drop(1))
    }

    @Test
    fun `a failed write does not stop the collection`() = runTest {
        failWrites = true
        backgroundScope.launch { persister.run() }
        runCurrent()
        failWrites = false
        persistFlow.value = false
        runCurrent()

        assertEquals(2, writes.size)
        assertSame(masterKey, writes[0]?.masterKey)
        assertNull(writes[1])
    }

    @Test
    fun `running again after cancellation writes again`() = runTest {
        val first = backgroundScope.launch { persister.run() }
        runCurrent()
        first.cancelAndJoin()
        backgroundScope.launch { persister.run() }
        runCurrent()

        assertEquals(2, writes.size)
        writes.forEach { assertSame(masterKey, it?.masterKey) }
    }
}
