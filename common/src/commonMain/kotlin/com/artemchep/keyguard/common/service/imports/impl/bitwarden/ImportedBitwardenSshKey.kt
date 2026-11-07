package com.artemchep.keyguard.common.service.imports.impl.bitwarden

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.export.entity.ItemSshKeyExportEntity
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.sshKeyOrNote
import com.artemchep.keyguard.common.service.imports.mapping.hasPublicKey
import com.artemchep.keyguard.common.service.imports.mapping.importedSshKeyFallbackFields
import com.artemchep.keyguard.common.service.imports.mapping.importedSshKeyOrNull
import com.artemchep.keyguard.common.service.imports.mapping.mismatchedSourceFields
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenTransportFields
import kotlinx.collections.immutable.toPersistentList

/**
 * The SSH key of an item, and the [type] and leading [fields] it imports as.
 */
internal class ImportedBitwardenSshKey(
    val type: DSecret.Type,
    val sshKey: CreateRequest.SshKey,
    val fields: List<DSecret.Field> = emptyList(),
)

/**
 * The SSH key [entity] of an item of [type]. A complete key, or no key at all, is
 * kept as it is: a vault's key is never rewritten, even one this version
 * cannot read.
 *
 * Vaultwarden drops a key that lacks its private key, public key or
 * fingerprint, so the missing ones are derived from the private key; the
 * private key and a matching public key stay as written. A key that does not
 * convert becomes a note and is counted, see [sshKeyOrNote].
 */
internal fun ImportPlanBuilder.importedBitwardenSshKey(
    entity: ItemSshKeyExportEntity?,
    title: String?,
    type: DSecret.Type,
    transport: BitwardenTransportFields.Decoded,
    sshKeyImportService: SshKeyImportService,
): ImportedBitwardenSshKey {
    val sshKey = entity.toCreateRequest(transport)
    val parts = listOf(sshKey.privateKey, sshKey.publicKey, sshKey.fingerprint)
    val keep = type != DSecret.Type.SshKey ||
        parts.none { it.isNullOrBlank() } ||
        parts.all { it.isNullOrBlank() }
    val derived = if (keep) null else importedSshKeyOrNull(sshKey.privateKey, sshKeyImportService)
    return when {
        keep -> ImportedBitwardenSshKey(type, sshKey)

        derived == null -> {
            skip(ImportSkipReason.SshKey, title)
            ImportedBitwardenSshKey(
                type = DSecret.Type.SecureNote,
                sshKey = CreateRequest.SshKey(),
                fields = importedSshKeyFallbackFields(
                    privateKeys = listOf(sshKey.privateKey),
                    publicKey = sshKey.publicKey,
                    fingerprint = sshKey.fingerprint,
                ),
            )
        }

        else -> ImportedBitwardenSshKey(
            type = DSecret.Type.SshKey,
            sshKey = derived.copy(
                privateKey = sshKey.privateKey,
                publicKey = sshKey.publicKey
                    ?.takeIf(derived::hasPublicKey)
                    ?: derived.publicKey,
                certificate = sshKey.certificate,
                bookmarks = sshKey.bookmarks,
            ),
            fields = derived.mismatchedSourceFields(sshKey.publicKey, sshKey.fingerprint),
        )
    }
}

private fun ItemSshKeyExportEntity?.toCreateRequest(
    transport: BitwardenTransportFields.Decoded,
) = CreateRequest.SshKey(
    privateKey = this?.privateKey,
    publicKey = this?.publicKey,
    fingerprint = this?.keyFingerprint,
    certificate = transport.certificate,
    bookmarks = transport.bookmarks.map { bookmark ->
        DSecret.SshKey.Bookmark(name = bookmark.name, url = bookmark.url)
    }.toPersistentList(),
)
