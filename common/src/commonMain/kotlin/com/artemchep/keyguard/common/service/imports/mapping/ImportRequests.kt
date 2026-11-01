package com.artemchep.keyguard.common.service.imports.mapping

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import kotlinx.collections.immutable.toPersistentList

private const val BOOLEAN_TRUE = "true"
private const val BOOLEAN_FALSE = "false"

/**
 * A planned vault item of [type] with the members every import has to set:
 * the vault rejects a request without a [CreateRequest.favorite] or
 * [CreateRequest.reprompt] flag, and the plan carries no ownership.
 *
 * A blank text value becomes `null`, so the review and the vault never show an
 * empty field the source did not mean to have.
 */
internal fun importedRequest(
    type: DSecret.Type,
    meta: ImportedItemMeta,
    fields: List<DSecret.Field> = emptyList(),
): CreateRequest = CreateRequest(
    type = type,
    now = meta.now,
    title = meta.title.orNullIfBlank(),
    note = meta.note.orNullIfBlank(),
    favorite = meta.favorite,
    reprompt = meta.reprompt,
    uris = meta.uris.toPersistentList(),
    fields = fields.toPersistentList(),
    tags = meta.tags
        .mapNotNull { it.orNullIfBlank() }
        .distinct()
        .toPersistentList(),
)

internal fun importedLogin(
    username: String?,
    password: String?,
    totp: String? = null,
): CreateRequest.Login = CreateRequest.Login(
    username = username.orNullIfBlank(),
    // Whitespace is a valid password; only an empty value means absent.
    password = password?.takeIf { it.isNotEmpty() },
    totp = totp,
)

/**
 * A custom field, or `null` when [value] is blank. Hidden fields preserve
 * whitespace-only secrets and omit only empty values. A blank [name] falls
 * back to [IMPORT_DEFAULT_FIELD_LABEL], because the vault rejects a nameless field.
 */
internal fun importedFieldOrNull(
    name: String?,
    value: String?,
    hidden: Boolean = false,
): DSecret.Field? = importedFieldOrNull(
    name = name,
    value = value,
    type = if (hidden) {
        DSecret.Field.Type.Hidden
    } else {
        DSecret.Field.Type.Text
    },
)

/**
 * [importedFieldOrNull] of the given [type]. The vault rejects a boolean field
 * that holds anything but `true` or `false`, so a value that reads as neither,
 * such as `1` or `yes`, stays as text: carrying it as text loses less than
 * dropping the field.
 */
internal fun importedFieldOrNull(
    name: String?,
    value: String?,
    type: DSecret.Field.Type,
): DSecret.Field? {
    val text = value
        ?.takeIf { if (type == DSecret.Field.Type.Hidden) it.isNotEmpty() else it.isNotBlank() }
        ?: return null
    val boolean = text.trim().lowercase()
        .takeIf { type == DSecret.Field.Type.Boolean && (it == BOOLEAN_TRUE || it == BOOLEAN_FALSE) }
    return DSecret.Field(
        name = name.orNullIfBlank() ?: IMPORT_DEFAULT_FIELD_LABEL,
        value = boolean ?: text,
        type = when {
            boolean != null -> DSecret.Field.Type.Boolean
            type == DSecret.Field.Type.Boolean -> DSecret.Field.Type.Text
            else -> type
        },
    )
}

internal fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

/**
 * The title a planned item is written with: its own, or [untitledTitle] when the
 * source left it blank.
 *
 * Blank rather than merely absent: a title may legally be whitespace, e.g. in a CXF
 * document, and both writing that verbatim and rendering it produce something that
 * looks like a bug.
 */
internal fun CreateRequest.resolveTitle(
    untitledTitle: String,
): String = title
    ?.takeIf { it.isNotBlank() }
    ?: untitledTitle
