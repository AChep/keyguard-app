package com.artemchep.keyguard.common.service.export

import com.artemchep.keyguard.common.model.DSecret
import kotlinx.io.Sink

interface KdbxExportService {
    /**
     * Exports given content into a KeePass database protected
     * by the [password]. Folders become groups; organizations and
     * collections have no KeePass equivalent and are skipped.
     *
     * @param loadAttachment loads the content of an attachment,
     * or `null` to export the items without attachments.
     */
    suspend fun export(
        sink: Sink,
        password: String,
        data: ExportVaultData,
        loadAttachment: KdbxAttachmentLoader?,
    )
}

fun interface KdbxAttachmentLoader {
    /**
     * Writes the content of the [attachment] to the [sink].
     */
    suspend fun load(
        secret: DSecret,
        attachment: DSecret.Attachment,
        sink: Sink,
    )
}
