package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.data.Database

/**
 * Accumulates the local SQLite writes that the KeePass sync run intends to
 * make, instead of committing them immediately.
 *
 * @param db the committed database, used for overlay reads and pre-image
 *   capture while the sync runs.
 */
class KeePassWriteBackBuffer(
    private val db: Database,
) {
    /** A pending write. A `null` [value] is a staged deletion. */
    private class Staged<T>(
        val value: T?,
        val preImage: T?,
    )

    private val ciphers = LinkedHashMap<String, Staged<BitwardenCipher>>()
    private val folders = LinkedHashMap<String, Staged<BitwardenFolder>>()

    /** True when nothing has been staged and there is nothing to commit. */
    val isEmpty: Boolean get() = ciphers.isEmpty() && folders.isEmpty()

    //
    // DB reads
    //

    private fun readCipherFromDb(cipherId: String): BitwardenCipher? =
        db.cipherQueries
            .getByCipherId(cipherId = cipherId)
            .executeAsOneOrNull()
            ?.data_

    private fun readFolderFromDb(folderId: String): BitwardenFolder? =
        db.folderQueries
            .getByFolderId(folderId = folderId)
            .executeAsOneOrNull()
            ?.data_

    //
    // Overlay reads
    //

    fun readCipher(cipherId: String): BitwardenCipher? {
        val staged = ciphers[cipherId]
        return if (staged != null) staged.value else readCipherFromDb(cipherId)
    }

    fun readFolder(folderId: String): BitwardenFolder? {
        val staged = folders[folderId]
        return if (staged != null) staged.value else readFolderFromDb(folderId)
    }

    fun listCiphers(accountId: String): List<BitwardenCipher> {
        val merged = db.cipherQueries
            .getByAccountId(accountId = accountId)
            .executeAsList()
            .associateTo(LinkedHashMap()) { it.data_.cipherId to it.data_ }
        for ((id, staged) in ciphers) {
            val value = staged.value
            if (value == null) merged.remove(id) else merged[id] = value
        }
        return merged.values.toList()
    }

    fun listFolders(accountId: String): List<BitwardenFolder> {
        val merged = db.folderQueries
            .getByAccountId(accountId = accountId)
            .executeAsList()
            .associateTo(LinkedHashMap()) { it.data_.folderId to it.data_ }
        for ((id, staged) in folders) {
            val value = staged.value
            if (value == null) merged.remove(id) else merged[id] = value
        }
        return merged.values.toList()
    }

    //
    // Staging
    //

    fun stageCipherUpsert(cipher: BitwardenCipher) {
        ciphers[cipher.cipherId] = Staged(
            value = cipher,
            preImage = preImageCipher(cipher.cipherId),
        )
    }

    fun stageCipherDelete(cipherId: String) {
        ciphers[cipherId] = Staged(
            value = null,
            preImage = preImageCipher(cipherId),
        )
    }

    fun stageFolderUpsert(folder: BitwardenFolder) {
        folders[folder.folderId] = Staged(
            value = folder,
            preImage = preImageFolder(folder.folderId),
        )
    }

    fun stageFolderDelete(folderId: String) {
        folders[folderId] = Staged(
            value = null,
            preImage = preImageFolder(folderId),
        )
    }

    /**
     * The committed row as it was before this sync first touched [cipherId].
     * Captured once and preserved across repeated staging of the same id.
     */
    private fun preImageCipher(cipherId: String): BitwardenCipher? {
        val existing = ciphers[cipherId]
        return if (existing != null) existing.preImage else readCipherFromDb(cipherId)
    }

    private fun preImageFolder(folderId: String): BitwardenFolder? {
        val existing = folders[folderId]
        return if (existing != null) existing.preImage else readFolderFromDb(folderId)
    }

    //
    // Commit
    //

    /**
     * Applies every staged write to SQLite in a single transaction. Must be
     * called only after the `.kdbx` flush has succeeded.
     *
     * Each row is re-read from [db] and applied only when it still matches the
     * pre-image captured at staging time; a row changed concurrently by the
     * user (outside this sync) is left untouched so their edit is not lost —
     * the next sync reconciles it against the already updated `.kdbx`.
     */
    fun commit(db: Database) {
        if (isEmpty) return
        db.cipherQueries.transaction {
            for ((id, staged) in folders) {
                val current = db.folderQueries
                    .getByFolderId(folderId = id)
                    .executeAsOneOrNull()
                    ?.data_
                if (current != staged.preImage) continue
                val value = staged.value
                if (value == null) {
                    db.folderQueries.deleteByFolderId(folderId = id)
                } else {
                    db.folderQueries.insert(
                        folderId = value.folderId,
                        accountId = value.accountId,
                        data = value,
                    )
                }
            }
            for ((id, staged) in ciphers) {
                val current = db.cipherQueries
                    .getByCipherId(cipherId = id)
                    .executeAsOneOrNull()
                    ?.data_
                if (current != staged.preImage) continue
                val value = staged.value
                if (value == null) {
                    db.cipherQueries.deleteByCipherId(cipherId = id)
                } else {
                    db.cipherQueries.insert(
                        folderId = value.folderId,
                        accountId = value.accountId,
                        cipherId = value.cipherId,
                        data = value,
                        updatedAt = value.revisionDate,
                    )
                }
            }
        }
    }
}
