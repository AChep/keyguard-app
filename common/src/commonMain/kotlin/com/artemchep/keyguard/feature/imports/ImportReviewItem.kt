package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.mapping.resolveTitle
import com.artemchep.keyguard.feature.transfer.TransferItem
import com.artemchep.keyguard.feature.transfer.sortedTransferItemsBy

internal data class ImportReviewItem(
    val sourceIndex: Int,
    val item: TransferItem,
)

/**
 * Projects the planned vault writes onto the shared review rows.
 *
 * Reads the [CreateRequest]s rather than the source document on purpose: the review
 * has to describe what Keyguard will actually *write*, which is not always what
 * arrived — a credential the mapper could not represent is a counted skip and must
 * not appear here as though it were coming across.
 *
 * [untitledLabel] is a translated placeholder because an item title may legally be
 * blank, e.g. in a CXF document, and a row with no text at all reads as a rendering bug. It goes through
 * [resolveTitle], the very function the commit writes the title with, so the row cannot
 * name a title the vault item will not carry.
 */
internal fun ImportPlan.toUiItems(
    untitledLabel: String,
): List<ImportReviewItem> {
    val items = items.mapIndexed { index, item ->
        IndexedValue(
            index = index,
            value = item,
        )
    }.sortedTransferItemsBy { indexedItem ->
        indexedItem.value.request.resolveTitle(untitledLabel)
    }
    return items.mapIndexed { index, indexedItem ->
        val shapeState = getShapeState(
            list = items,
            index = index,
            predicate = { _, _ -> true },
        )
        val request = indexedItem.value.request
        ImportReviewItem(
            sourceIndex = indexedItem.index,
            item = TransferItem(
                title = request.resolveTitle(untitledLabel),
                shapeState = shapeState,
                credentials = request.toUiKinds(),
            ),
        )
    }
}

/**
 * The credential kinds one planned item carries, in [TransferItem.Kind]
 * declaration order so a row reads the same as on the export screen.
 */
private fun CreateRequest.toUiKinds(): List<TransferItem.Kind> = buildList {
    if (fido2Credentials.isNotEmpty()) {
        add(TransferItem.Kind.Passkey)
    }
    if (!login.password.isNullOrEmpty()) {
        add(TransferItem.Kind.Password)
    }
    if (login.totp != null) {
        add(TransferItem.Kind.Totp)
    }
    val typeKind = type.toUiKindOrNull()
    if (typeKind != null && typeKind < TransferItem.Kind.Fields) {
        add(typeKind)
    }
    if (fields.isNotEmpty()) {
        add(TransferItem.Kind.Fields)
    }
    // After the fields, matching both the enum's declaration order and the export
    // wire, where the ssh-key credential follows the user's custom fields.
    if (typeKind != null && typeKind > TransferItem.Kind.Fields) {
        add(typeKind)
    }
}

/**
 * The badge of the item type itself. Exhaustive, so a new item type does not
 * compile until it has one.
 */
private fun DSecret.Type?.toUiKindOrNull(): TransferItem.Kind? = when (this) {
    DSecret.Type.Card -> TransferItem.Kind.Card

    DSecret.Type.Identity -> TransferItem.Kind.Identity

    // A note *item* is a note; a note riding along on another type is a field of
    // it rather than a credential of its own, which is why this reads `type` and
    // not `note`.
    DSecret.Type.SecureNote -> TransferItem.Kind.Note

    DSecret.Type.SshKey -> TransferItem.Kind.SshKey

    DSecret.Type.GpgKey -> TransferItem.Kind.GpgKey

    DSecret.Type.Environment -> TransferItem.Kind.Environment

    // A login is the password, passkeys and one-time password above.
    DSecret.Type.Login,
    DSecret.Type.None,
    null,
    -> null
}
