package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.ops

import app.keemobile.kotpass.database.modifiers.binaries
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.util.to0DigitsNanosOfSecond
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.entity.KeePassCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassDbMutator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.KeePassWriteBackBuffer
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec.KeePassCipherCodec
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.writeIfCurrent
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import kotlin.uuid.Uuid

class KeePassCipherSyncOps(
    private val accountId: String,
    private val buffer: KeePassWriteBackBuffer,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherCodec: KeePassCipherCodec,
    private val mutator: KeePassDbMutator,
    private val remoteToLocalFolders: Map<String, String>,
    private val localToRemoteFolders: Map<String, String?>,
) : EntitySyncOps<BitwardenCipher, KeePassCipher> {
    override suspend fun readLocal(localId: String): BitwardenCipher? =
        buffer.readCipher(localId)

    override suspend fun insertOrUpdateLocally(entries: List<Pair<KeePassCipher, BitwardenCipher?>>) {
        entries.forEach { (server, local) ->
            val folderId = remoteToLocalFolders[server.group.uuid.toString()]
            val cipherId = local?.cipherId ?: cryptoGenerator.uuid()
            val decoded = cipherCodec.decode(
                accountId = accountId,
                folderId = folderId,
                cipherId = cipherId,
                remote = server.cipher,
                local = local,
                revisionDate = server.revisionDate,
                binaries = mutator.database.binaries,
            )
            buffer.stageCipherUpsert(decoded)
        }
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<KeePassCipher, BitwardenCipher>>,
    ): LocalUpdateResult {
        var applied = 0
        var skipped = 0
        entries.forEach { entry ->
            val server = entry.server
            val folderId = remoteToLocalFolders[server.group.uuid.toString()]
            val cipher = cipherCodec.decode(
                accountId = accountId,
                folderId = folderId,
                cipherId = entry.initialLocal.cipherId,
                remote = server.cipher,
                local = entry.initialLocal,
                revisionDate = server.revisionDate,
                binaries = mutator.database.binaries,
            )
            val current = buffer.readCipher(entry.localId)
            if (entry.writeIfCurrent(current) { buffer.stageCipherUpsert(cipher) }) {
                applied++
            } else {
                skipped++
            }
        }
        return LocalUpdateResult(applied = applied, skipped = skipped)
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        localIds.forEach(buffer::stageCipherDelete)
    }

    override suspend fun saveLocal(local: BitwardenCipher, previousLocal: BitwardenCipher?) {
        buffer.stageCipherUpsert(local)
    }

    override suspend fun pushToServer(
        local: BitwardenCipher,
        server: KeePassCipher?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenCipher> {
        val remoteUuid = server?.cipher?.uuid
        val revisionDate = local.revisionDate.to0DigitsNanosOfSecond()
        val encoded = cipherCodec.encode(
            local = local,
            remote = server?.cipher,
            existingBinaries = mutator.database.binaries,
        )
        val newEntry = encoded.entry

        val newLocal = local.copy(
            service = BitwardenService(
                remote = BitwardenService.Remote(
                    id = newEntry.uuid.toString(),
                    revisionDate = revisionDate,
                    deletedDate = local.deletedDate?.to0DigitsNanosOfSecond(),
                ),
                version = BitwardenService.VERSION,
            ),
            revisionDate = revisionDate,
            expiredDate = local.expiredDate?.to0DigitsNanosOfSecond(),
            deletedDate = local.deletedDate?.to0DigitsNanosOfSecond(),
            attachments = encoded.attachments,
            sourceData = encoded.sourceData,
        )

        mutator.addBinaries(encoded.binaryAdditions)

        if (remoteUuid != null) {
            val updated = mutator.modifyEntry(remoteUuid) { newEntry }
            if (updated) {
                val currentGroupUuid = server.group.uuid
                val targetFolderUuid = remoteGroupUuidForLocalFolder(local.folderId)
                if (targetFolderUuid != null && targetFolderUuid != currentGroupUuid) {
                    mutator.moveEntry(remoteUuid, targetFolderUuid)
                }
                return RemoteWriteOutcome.Upsert(newLocal)
            }
        }

        val targetGroupUuid = remoteGroupUuidForLocalFolder(local.folderId)
        mutator.addEntry(newEntry, parentGroupUuid = targetGroupUuid)
        return RemoteWriteOutcome.Upsert(newLocal)
    }

    override suspend fun deleteOnServer(
        local: BitwardenCipher,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenCipher> {
        val uuid = Uuid.parse(serverId)
        if (mutator.database.content.meta.recycleBinEnabled) {
            mutator.softDeleteEntry(uuid)
        } else {
            mutator.removeEntry(uuid)
        }
        return RemoteWriteOutcome.DeleteLocal
    }

    override suspend fun mergeConflict(
        local: BitwardenCipher,
        server: KeePassCipher,
    ): RemoteWriteOutcome<BitwardenCipher> {
        val folderId = remoteToLocalFolders[server.group.uuid.toString()]
        val decoded = cipherCodec.decode(
            accountId = accountId,
            folderId = folderId,
            cipherId = local.cipherId,
            remote = server.cipher,
            local = local,
            revisionDate = server.revisionDate,
            binaries = mutator.database.binaries,
        )
        return RemoteWriteOutcome.Upsert(decoded)
    }

    private fun remoteGroupUuidForLocalFolder(folderId: String?): Uuid? {
        val remoteId = folderId?.let { localFolderId ->
            if (localFolderId in localToRemoteFolders) {
                localToRemoteFolders[localFolderId]
            } else {
                localFolderId.takeIf { it in remoteToLocalFolders }
            }
        } ?: return null
        return Uuid.parse(remoteId)
    }
}
