package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.ops

import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.TimeData
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ACCOUNT_ID
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassDbMutator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassWriteBackBuffer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.buildEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.KeePassCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestKeePassDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.strategy.KeePassCipherSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testCryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.toKeePassDiffKey
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncPlanBuilder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Suppress("FunctionNaming")
class KeePassCipherSyncOpsMergeTest {
    @Test
    fun `conflict merges independent edits writes KDBX and converges`() = runTest {
        val codec = createTestCipherCodec()
        val baseEntry = secureNoteEntry(
            title = "Base name",
            notes = "Base notes",
            revisionDate = BASE_REVISION,
        )
        val base = decode(codec, baseEntry, BASE_REVISION)
        val local = base.copy(
            name = "Local name",
            keyBase64 = "local-cipher-key",
            revisionDate = LOCAL_REVISION,
        )
        val remoteEntry = secureNoteEntry(
            title = "Base name",
            notes = "Remote notes",
            revisionDate = REMOTE_REVISION,
        )
        val mutator = KeePassDbMutator(createTestKeePassDatabase()).also {
            it.addEntry(remoteEntry)
        }
        val ops = createOps(codec, mutator)
        val server = remoteCipher(mutator, remoteEntry, REMOTE_REVISION)

        val outcome = assertIs<RemoteWriteOutcome.Upsert<*>>(
            ops.mergeConflict(local = local, server = server),
        )
        val merged = assertIs<BitwardenCipher>(outcome.local)
        val publishedEntry = mutator.database.content.group.entries.single()

        assertEquals("Local name", merged.name)
        assertEquals("Remote notes", merged.notes)
        assertEquals(local.keyBase64, merged.keyBase64)
        assertEquals("Local name", publishedEntry.fields.title?.content)
        assertEquals("Remote notes", publishedEntry.fields.notes?.content)
        val snapshot = assertNotNull(merged.remoteEntity)
        assertNull(snapshot.remoteEntity)
        assertEquals(
            merged.copy(
                keyBase64 = null,
                remoteEntity = null,
            ),
            snapshot,
        )
        assertTrue(mutator.hasMutations)

        val publishedServer = remoteCipher(
            mutator = mutator,
            entry = publishedEntry,
            revisionDate = merged.revisionDate,
        )
        val plan = EntitySyncPlanBuilder(
            strategy = KeePassCipherSyncStrategy(
                remoteFolderIdToLocalId = { null },
            ),
            dateNormalizer = { it.toKeePassDiffKey() },
        ).buildPlan(
            localEntities = listOf(merged),
            serverEntities = listOf(publishedServer),
        )
        assertEquals(emptyList(), plan.actions)
    }

    @Test
    fun `existing entry write advances a same-second local revision`() = runTest {
        val codec = createTestCipherCodec()
        val remoteEntry = secureNoteEntry(
            title = "Base name",
            notes = "Base notes",
            revisionDate = REMOTE_REVISION,
        )
        val local = decode(codec, remoteEntry, REMOTE_REVISION).copy(
            name = "Local name",
            revisionDate = SAME_SECOND_LOCAL_REVISION,
        )
        val mutator = KeePassDbMutator(createTestKeePassDatabase()).also {
            it.addEntry(remoteEntry)
        }
        val server = remoteCipher(mutator, remoteEntry, REMOTE_REVISION)

        val outcome = assertIs<RemoteWriteOutcome.Upsert<*>>(
            createOps(codec, mutator).pushToServer(
                local = local,
                server = server,
                force = false,
            ),
        )
        val publishedLocal = assertIs<BitwardenCipher>(outcome.local)
        val publishedEntry = mutator.database.content.group.entries.single()

        assertEquals(NEXT_REMOTE_REVISION, publishedEntry.times?.lastModificationTime)
        assertEquals(NEXT_REMOTE_REVISION, publishedLocal.revisionDate)
        assertEquals(NEXT_REMOTE_REVISION, publishedLocal.service.remote?.revisionDate)

        val plan = EntitySyncPlanBuilder(
            strategy = KeePassCipherSyncStrategy(
                remoteFolderIdToLocalId = { null },
            ),
            dateNormalizer = { it.toKeePassDiffKey() },
        ).buildPlan(
            localEntities = listOf(publishedLocal),
            serverEntities = listOf(
                remoteCipher(mutator, publishedEntry, NEXT_REMOTE_REVISION),
            ),
        )
        assertEquals(emptyList(), plan.actions)
    }

    @Test
    fun `conflict advances a clock-skewed remote revision`() = runTest {
        val codec = createTestCipherCodec()
        val baseEntry = secureNoteEntry(
            title = "Base name",
            notes = "Base notes",
            revisionDate = BASE_REVISION,
        )
        val base = decode(codec, baseEntry, BASE_REVISION)
        val local = base.copy(
            name = "Local name",
            revisionDate = LOCAL_REVISION,
        )
        val remoteEntry = secureNoteEntry(
            title = "Base name",
            notes = "Remote notes",
            revisionDate = FUTURE_REMOTE_REVISION,
        )
        val mutator = KeePassDbMutator(createTestKeePassDatabase()).also {
            it.addEntry(remoteEntry)
        }

        val outcome = assertIs<RemoteWriteOutcome.Upsert<*>>(
            createOps(codec, mutator).mergeConflict(
                local = local,
                server = remoteCipher(mutator, remoteEntry, FUTURE_REMOTE_REVISION),
            ),
        )
        val merged = assertIs<BitwardenCipher>(outcome.local)
        val publishedEntry = mutator.database.content.group.entries.single()

        assertEquals("Local name", merged.name)
        assertEquals("Remote notes", merged.notes)
        assertEquals(NEXT_FUTURE_REMOTE_REVISION, publishedEntry.times?.lastModificationTime)
        assertEquals(NEXT_FUTURE_REMOTE_REVISION, merged.revisionDate)
        assertEquals(NEXT_FUTURE_REMOTE_REVISION, merged.service.remote?.revisionDate)

        val plan = EntitySyncPlanBuilder(
            strategy = KeePassCipherSyncStrategy(
                remoteFolderIdToLocalId = { null },
            ),
            dateNormalizer = { it.toKeePassDiffKey() },
        ).buildPlan(
            localEntities = listOf(merged),
            serverEntities = listOf(
                remoteCipher(mutator, publishedEntry, NEXT_FUTURE_REMOTE_REVISION),
            ),
        )
        assertEquals(emptyList(), plan.actions)
    }

    @Test
    fun `conflict does not preserve losing local password in KDBX history`() = runTest {
        val codec = createTestCipherCodec()
        val baseEntry = loginEntry(
            password = "base-password",
            revisionDate = BASE_REVISION,
        )
        val base = decode(codec, baseEntry, BASE_REVISION)
        val local = base.copy(
            login = base.login?.copy(password = "local-password"),
            revisionDate = LOCAL_REVISION,
        )
        val remoteEntry = loginEntry(
            password = "remote-password",
            revisionDate = REMOTE_REVISION,
        )
        val mutator = KeePassDbMutator(createTestKeePassDatabase()).also {
            it.addEntry(remoteEntry)
        }
        val ops = createOps(codec, mutator)

        val outcome = assertIs<RemoteWriteOutcome.Upsert<*>>(
            ops.mergeConflict(
                local = local,
                server = remoteCipher(mutator, remoteEntry, REMOTE_REVISION),
            ),
        )
        val merged = assertIs<BitwardenCipher>(outcome.local)
        val publishedEntry = mutator.database.content.group.entries.single()
        val roundTripped = decode(
            codec = codec,
            entry = publishedEntry,
            revisionDate = merged.revisionDate,
        )

        assertEquals("remote-password", merged.login?.password)
        assertEquals(emptyList(), merged.passwordHistory)
        assertEquals(
            listOf("remote-password"),
            publishedEntry.history.map { it.fields.password?.content },
        )
        assertEquals(emptyList(), roundTripped.passwordHistory)
    }

    @Test
    fun `successful write keeps local password history without exporting it`() = runTest {
        val codec = createTestCipherCodec()
        val mutator = KeePassDbMutator(createTestKeePassDatabase())
        val ops = createOps(codec, mutator)
        val passwordHistory = listOf(
            BitwardenCipher.Login.PasswordHistory(
                password = "local-only-password",
                lastUsedDate = LOCAL_REVISION,
            ),
        )
        val local = testBitwardenCipher(cipherId = ITEM_ID).copy(
            revisionDate = LOCAL_REVISION,
            type = BitwardenCipher.Type.Login,
            secureNote = null,
            login = BitwardenCipher.Login(
                password = "current-password",
                uris = emptyList(),
            ),
            passwordHistory = passwordHistory,
        )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<*>>(
            ops.pushToServer(
                local = local,
                server = null,
                force = false,
            ),
        )
        val publishedLocal = assertIs<BitwardenCipher>(outcome.local)
        val publishedEntry = mutator.database.content.group.entries.single()

        assertEquals(passwordHistory, publishedLocal.passwordHistory)
        assertEquals(local.keyBase64, publishedLocal.keyBase64)
        assertEquals(emptyList(), publishedEntry.history)
        val snapshot = assertNotNull(publishedLocal.remoteEntity)
        assertNull(snapshot.keyBase64)
        assertEquals(emptyList(), snapshot.passwordHistory)
    }

    private suspend fun decode(
        codec: KeePassCipherCodec,
        entry: Entry,
        revisionDate: Instant,
    ) = codec.decode(
        accountId = ACCOUNT_ID,
        folderId = null,
        cipherId = ITEM_ID,
        remote = entry,
        local = null,
        revisionDate = revisionDate,
        binaries = emptyMap(),
    )

    private fun createOps(
        codec: KeePassCipherCodec,
        mutator: KeePassDbMutator,
    ) = KeePassCipherSyncOps(
        accountId = ACCOUNT_ID,
        buffer = KeePassWriteBackBuffer(createTestDatabase()),
        cryptoGenerator = testCryptoGenerator,
        cipherCodec = codec,
        mutator = mutator,
        remoteToLocalFolders = emptyMap(),
        localToRemoteFolders = emptyMap(),
    )

    private fun remoteCipher(
        mutator: KeePassDbMutator,
        entry: Entry,
        revisionDate: Instant,
    ) = KeePassCipher(
        group = mutator.database.content.group,
        cipher = entry,
        revisionDate = revisionDate,
    )

    private fun secureNoteEntry(
        title: String,
        notes: String,
        revisionDate: Instant,
    ) = buildEntry(
        uuid = Uuid.parse(ITEM_ID),
        title = title,
        notes = notes,
    ).copy(times = TimeData.create(revisionDate))

    private fun loginEntry(
        password: String,
        revisionDate: Instant,
    ) = buildEntry(
        uuid = Uuid.parse(ITEM_ID),
        title = "Login",
        username = "alice",
        password = password,
    ).copy(times = TimeData.create(revisionDate))
}

private const val ITEM_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private val BASE_REVISION = Instant.parse("2024-01-01T00:00:00Z")
private val LOCAL_REVISION = Instant.parse("2024-01-02T00:00:00Z")
private val REMOTE_REVISION = Instant.parse("2024-01-03T00:00:00Z")
private val SAME_SECOND_LOCAL_REVISION = Instant.parse("2024-01-03T00:00:00.500Z")
private val NEXT_REMOTE_REVISION = Instant.parse("2024-01-03T00:00:01Z")
private val FUTURE_REMOTE_REVISION = Instant.parse("2099-01-01T00:00:00Z")
private val NEXT_FUTURE_REMOTE_REVISION = Instant.parse("2099-01-01T00:00:01Z")
