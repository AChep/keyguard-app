package com.artemchep.keyguard.common.service.imports.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.ImportedTotp
import com.artemchep.keyguard.common.service.imports.mapping.importedLogin
import com.artemchep.keyguard.common.service.imports.mapping.importedRequest
import com.artemchep.keyguard.common.service.imports.mapping.importedSshKeyFallbackFields
import com.artemchep.keyguard.common.service.imports.mapping.importedSshKeyOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedTotp
import com.artemchep.keyguard.common.service.imports.mapping.mismatchedSourceFields

/**
 * Plans an item of [type] that [meta] describes. [content] fills in the
 * members of its type, such as the card of a card.
 */
internal fun ImportPlanBuilder.item(
    meta: ImportedItemMeta,
    type: DSecret.Type,
    fields: List<DSecret.Field> = emptyList(),
    attachments: List<ImportPlan.Attachment> = emptyList(),
    content: CreateRequest.() -> CreateRequest = { this },
) = item(
    request = importedRequest(type, meta, fields).content(),
    folderKey = meta.folderKey,
    attachments = attachments,
    archived = meta.archived,
    trashed = meta.trashed,
)

/**
 * Plans a login. An unusable one-time password is dropped and counted; the
 * login itself still imports.
 */
internal fun ImportPlanBuilder.login(
    meta: ImportedItemMeta,
    username: String?,
    password: String?,
    totp: String? = null,
    fields: List<DSecret.Field> = emptyList(),
    attachments: List<ImportPlan.Attachment> = emptyList(),
) {
    val login = importedLogin(
        username = username,
        password = password,
        totp = importedTotpOrCount(totp, meta.title),
    )
    item(meta, DSecret.Type.Login, fields, attachments) {
        copy(login = login)
    }
}

/**
 * The one-time password to store, or `null`; an unusable one is counted.
 */
internal fun ImportPlanBuilder.importedTotpOrCount(
    raw: String?,
    title: String?,
): String? = when (val totp = importedTotp(raw)) {
    is ImportedTotp.Valid -> totp.raw
    ImportedTotp.Absent -> null
    ImportedTotp.Invalid -> {
        skip(ImportSkipReason.Otp, title)
        null
    }
}

/**
 * Plans an SSH key from the first of [privateKeys] that converts, see
 * [importedSshKeyOrNull]. If none does, or there is none, the item becomes a
 * note and the key is counted: an SSH key item with an unreadable private
 * key would still be offered by the SSH agent, which could not sign with it.
 *
 * [fields] gets the converted key, or `null`, and returns the item's fields;
 * a note must keep the private key as written.
 */
internal fun ImportPlanBuilder.sshKeyOrNote(
    meta: ImportedItemMeta,
    privateKeys: List<String?>,
    sshKeyImportService: SshKeyImportService,
    passphrase: String? = null,
    attachments: List<ImportPlan.Attachment> = emptyList(),
    fields: (CreateRequest.SshKey?) -> List<DSecret.Field>,
) {
    val sshKey = privateKeys.firstNotNullOfOrNull { privateKey ->
        importedSshKeyOrNull(privateKey, sshKeyImportService, passphrase)
    }
    if (sshKey == null) {
        skip(ImportSkipReason.SshKey, meta.title)
    }
    item(
        meta = meta,
        type = if (sshKey != null) DSecret.Type.SshKey else DSecret.Type.SecureNote,
        fields = fields(sshKey),
        attachments = attachments,
    ) {
        copy(sshKey = sshKey ?: CreateRequest.SshKey())
    }
}

/**
 * [sshKeyOrNote] for a source whose key has no labels of its own. The key,
 * and a [publicKey] or [fingerprint] that disagrees with it, are kept under
 * the shared labels ahead of the other [fields].
 */
internal fun ImportPlanBuilder.sshKeyOrNote(
    meta: ImportedItemMeta,
    privateKeys: List<String?>,
    publicKey: String?,
    fingerprint: String?,
    sshKeyImportService: SshKeyImportService,
    fields: List<DSecret.Field>,
    attachments: List<ImportPlan.Attachment> = emptyList(),
) = sshKeyOrNote(
    meta = meta,
    privateKeys = privateKeys,
    sshKeyImportService = sshKeyImportService,
    attachments = attachments,
) { sshKey ->
    val keyFields = sshKey?.mismatchedSourceFields(publicKey, fingerprint)
        ?: importedSshKeyFallbackFields(privateKeys, publicKey, fingerprint)
    keyFields + fields
}

/**
 * The path of a folder in an id-and-parent tree, root first. A parent that
 * does not exist ends the path; so does a cycle.
 */
internal fun <K : Any> importedFolderPath(
    id: K,
    parentOf: (K) -> K?,
    titleOf: (K) -> String?,
): List<String> {
    val seen = mutableSetOf<K>()
    val path = mutableListOf<String>()
    var current: K? = id
    while (current != null && seen.add(current) && path.size < IMPORT_MAX_FOLDER_DEPTH) {
        val title = titleOf(current)
            ?: break
        path += title
        current = parentOf(current)
    }
    return path.asReversed()
}
