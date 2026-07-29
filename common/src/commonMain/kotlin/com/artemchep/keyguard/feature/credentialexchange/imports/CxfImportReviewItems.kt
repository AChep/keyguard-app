package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.feature.credentialexchange.CredentialExchangeItem
import com.artemchep.keyguard.feature.credentialexchange.sortedCredentialExchangeItemsBy

internal data class CxfImportReviewItem(
    val sourceIndex: Int,
    val item: CredentialExchangeItem,
)

/**
 * Projects the planned vault writes onto the shared review rows.
 *
 * Reads the [CreateRequest]s rather than the source document on purpose: the review
 * has to describe what Keyguard will actually *write*, which is not always what
 * arrived — a credential the mapper could not represent is a counted skip and must
 * not appear here as though it were coming across.
 *
 * [untitledLabel] is a translated placeholder because a CXF item title may legally be
 * blank, and a row with no text at all reads as a rendering bug. It goes through
 * [resolveTitle], the very function the commit writes the title with, so the row cannot
 * name a title the vault item will not carry.
 */
internal fun CxfImportPlan.toUiItems(
    untitledLabel: String,
): List<CxfImportReviewItem> {
    val items = items.mapIndexed { index, item ->
        IndexedValue(
            index = index,
            value = item,
        )
    }.sortedCredentialExchangeItemsBy { indexedItem ->
        indexedItem.value.request.resolveTitle(untitledLabel)
    }
    return items.mapIndexed { index, indexedItem ->
        val shapeState = getShapeState(
            list = items,
            index = index,
            predicate = { _, _ -> true },
        )
        val request = indexedItem.value.request
        CxfImportReviewItem(
            sourceIndex = indexedItem.index,
            item = CredentialExchangeItem(
                title = request.resolveTitle(untitledLabel),
                shapeState = shapeState,
                credentials = request.toUiKinds(),
            ),
        )
    }
}

/**
 * The credential kinds one planned item carries, in [CredentialExchangeItem.Kind]
 * declaration order so a row reads the same as on the export screen.
 */
private fun CreateRequest.toUiKinds(): List<CredentialExchangeItem.Kind> = buildList {
    if (fido2Credentials.isNotEmpty()) {
        add(CredentialExchangeItem.Kind.Passkey)
    }
    if (!login.password.isNullOrEmpty()) {
        add(CredentialExchangeItem.Kind.Password)
    }
    if (login.totp != null) {
        add(CredentialExchangeItem.Kind.Totp)
    }
    when (type) {
        DSecret.Type.Card -> add(CredentialExchangeItem.Kind.Card)
        DSecret.Type.Identity -> add(CredentialExchangeItem.Kind.Identity)
        // A note *item* is a note; a note riding along on another type is a field of
        // it rather than a credential of its own, which is why this reads `type` and
        // not `note`.
        DSecret.Type.SecureNote -> add(CredentialExchangeItem.Kind.Note)
        else -> Unit
    }
    if (fields.isNotEmpty()) {
        add(CredentialExchangeItem.Kind.Fields)
    }
    // Last, matching both the enum's declaration order and the export wire, where
    // the ssh-key credential follows the user's custom fields.
    if (type == DSecret.Type.SshKey) {
        add(CredentialExchangeItem.Kind.SshKey)
    }
}
