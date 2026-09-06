package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.ops

import app.keemobile.kotpass.models.Group
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.crypto.NativeGpgCertificateMaterialReconciler
import com.artemchep.keyguard.crypto.NativeGpgKeyMetadataResolver
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ACCOUNT_ID
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassDbMutator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassWriteBackBuffer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestKeePassDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenFolder
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testCryptoGenerator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.KeePassFolderCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Suppress("FunctionNaming")
class KeePassSyncOpsTimestampTest {
    @Test
    fun `cipher push publishes one canonical revision everywhere`() = runTest {
        val mutator = KeePassDbMutator(createTestKeePassDatabase())
        val ops = KeePassCipherSyncOps(
            accountId = ACCOUNT_ID,
            buffer = KeePassWriteBackBuffer(createTestDatabase()),
            cryptoGenerator = testCryptoGenerator,
            cipherCodec = createTestCipherCodec(),
            mutator = mutator,
            remoteToLocalFolders = emptyMap(),
            localToRemoteFolders = emptyMap(),
            gpgCertificateMaterialReconciler = NativeGpgCertificateMaterialReconciler,
            gpgKeyMetadataResolver = NativeGpgKeyMetadataResolver,
        )
        val local = testBitwardenCipher(cipherId = ITEM_ID).copy(
            revisionDate = FRACTIONAL_REVISION,
            favorite = true,
        )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<*>>(
            ops.pushToServer(local = local, server = null, force = false),
        )
        val publishedLocal = assertIs<BitwardenCipher>(outcome.local)
        val publishedEntry = mutator.database.content.group.entries.single()

        assertEquals(CANONICAL_REVISION, publishedEntry.times?.lastModificationTime)
        assertEquals(CANONICAL_REVISION, publishedEntry.times?.lastAccessTime)
        assertEquals(CANONICAL_REVISION, publishedLocal.revisionDate)
        assertEquals(CANONICAL_REVISION, publishedLocal.service.remote?.revisionDate)
        assertEquals(true, publishedLocal.favorite)
    }

    @Test
    fun `folder push publishes one canonical revision everywhere`() = runTest {
        val remoteGroup = Group(
            uuid = Uuid.parse(ITEM_ID),
            name = "Before",
        )
        val remote = KeePassFolder(
            group = remoteGroup,
            name = remoteGroup.name,
            revisionDate = PAST_REVISION,
        )
        val mutator = KeePassDbMutator(createTestKeePassDatabase()).also {
            it.addGroup(remoteGroup)
        }
        val ops = KeePassFolderSyncOps(
            accountId = ACCOUNT_ID,
            buffer = KeePassWriteBackBuffer(createTestDatabase()),
            cryptoGenerator = testCryptoGenerator,
            folderCodec = KeePassFolderCodec(),
            mutator = mutator,
        )
        val local = testBitwardenFolder(
            folderId = ITEM_ID,
            name = "After",
        ).copy(revisionDate = FRACTIONAL_REVISION)

        val outcome = assertIs<RemoteWriteOutcome.Upsert<*>>(
            ops.pushToServer(local = local, server = remote, force = false),
        )
        val publishedLocal = assertIs<BitwardenFolder>(outcome.local)
        val publishedGroup = mutator.database.content.group.groups.single()

        assertEquals(CANONICAL_REVISION, publishedGroup.times?.lastModificationTime)
        assertEquals(CANONICAL_REVISION, publishedGroup.times?.lastAccessTime)
        assertEquals(CANONICAL_REVISION, publishedLocal.revisionDate)
        assertEquals(CANONICAL_REVISION, publishedLocal.service.remote?.revisionDate)
        assertEquals("After", publishedGroup.name)
    }

}

private const val ITEM_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
private val FRACTIONAL_REVISION = Instant.parse("2024-01-01T00:00:00.865708Z")
private val CANONICAL_REVISION = Instant.parse("2024-01-01T00:00:00Z")
private val PAST_REVISION = Instant.parse("2023-12-31T00:00:00Z")
