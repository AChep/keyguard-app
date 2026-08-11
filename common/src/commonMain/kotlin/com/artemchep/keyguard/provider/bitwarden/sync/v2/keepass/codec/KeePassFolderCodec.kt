package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.TimeData
import com.artemchep.keyguard.common.model.FolderHierarchyMode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Parser contract for a KeePass group -> Bitwarden folder decode.
 *
 * Fields consumed:
 *
 * | KeePass/group value | Direction | Parser use                                |
 * |---------------------|-----------|-------------------------------------------|
 * | `Group.uuid`        | decode    | [BitwardenService.Remote.id].             |
 * | `Group.name`        | decode    | Folder display name.                      |
 * | `parentId` arg      | decode    | Bitwarden parent folder id.               |
 * | `revisionDate` arg  | decode    | Folder and remote-service revision date.  |
 *
 * Decode always marks the folder as not deleted and uses
 * [FolderHierarchyMode.ParentId]. The caller owns the local folder id, account
 * id, revision clock, and parent relationship.
 */
class KeePassFolderCodec {
    fun encodeNew(local: BitwardenFolder): Group =
        Group(
            uuid = runCatching {
                Uuid.parse(local.folderId)
            }.getOrElse { Uuid.random() },
            name = local.name,
            times = TimeData.create(now = local.revisionDate),
        )

    fun decode(
        accountId: String,
        folderId: String,
        remote: Group,
        local: BitwardenFolder?,
        revisionDate: Instant,
        parentId: String? = null,
    ): BitwardenFolder {
        val service = BitwardenService(
            remote = BitwardenService.Remote(
                id = remote.uuid.toString(),
                revisionDate = revisionDate,
                deletedDate = null,
            ),
            deleted = false,
            version = BitwardenService.VERSION,
        )
        return BitwardenFolder(
            accountId = accountId,
            folderId = folderId,
            revisionDate = revisionDate,
            service = service,
            name = remote.name,
            parentId = parentId,
            hierarchyMode = FolderHierarchyMode.ParentId,
        )
    }
}
