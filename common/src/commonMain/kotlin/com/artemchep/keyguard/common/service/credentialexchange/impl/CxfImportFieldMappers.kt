package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField

private const val BOOLEAN_TRUE = "true"
private const val BOOLEAN_FALSE = "false"

/**
 * The name given to an imported field whose source carries no label and that
 * has no fallback of its own. Keyguard refuses to store a nameless field, and
 * it refuses it while building the cipher — that is, it would fail the whole
 * import rather than the one field — so a generic name is the only lossless
 * option. Not translated, for the same reason as [DEFAULT_FOLDER_TITLE].
 */
internal const val DEFAULT_FIELD_LABEL = "Field"

/**
 * Maps imported custom fields into Keyguard fields. The inverse of
 * [mapCustomFields]; field kinds without a Keyguard counterpart become text.
 */
internal fun mapImportCustomFields(
    fields: List<CxfEditableField>,
): List<DSecret.Field> = fields.mapNotNull { field ->
    field.toFieldOrNull(fallbackLabel = null)
}

internal fun CxfEditableField.toFieldOrNull(
    fallbackLabel: String?,
): DSecret.Field? {
    val fieldValue = value.takeIf { it.isNotBlank() }
        ?: return null
    return importedField(
        name = label?.takeIf { it.isNotBlank() } ?: fallbackLabel,
        value = fieldValue,
        type = fieldTypeToFieldType(),
    )
}

/**
 * Builds a field that the vault is guaranteed to accept. Two of Keyguard's
 * field invariants are enforced while the cipher is created, so violating
 * either one fails the entire import transaction instead of the single
 * offending field — this is where both are absorbed:
 *
 * - a field must have a name, so an absent one becomes [DEFAULT_FIELD_LABEL];
 * - a boolean field must hold exactly `true` or `false`, so a value that
 *   cannot be canonicalized keeps its text verbatim and degrades to
 *   [DSecret.Field.Type.Text]. Other exporters legitimately emit `1`, `yes`
 *   or `True` here, and carrying the value as text loses less than dropping
 *   the field.
 */
internal fun importedField(
    name: String?,
    value: String,
    type: DSecret.Field.Type,
): DSecret.Field {
    val canonicalBoolean = value.trim().lowercase()
        .takeIf { it == BOOLEAN_TRUE || it == BOOLEAN_FALSE }
    val booleanField = type == DSecret.Field.Type.Boolean && canonicalBoolean != null
    return DSecret.Field(
        name = name ?: DEFAULT_FIELD_LABEL,
        value = canonicalBoolean?.takeIf { booleanField } ?: value,
        type = when {
            type != DSecret.Field.Type.Boolean -> type
            booleanField -> DSecret.Field.Type.Boolean
            else -> DSecret.Field.Type.Text
        },
    )
}

internal fun CxfEditableField.fieldTypeToFieldType(): DSecret.Field.Type = when (fieldType) {
    CxfEditableField.FIELD_TYPE_CONCEALED_STRING -> DSecret.Field.Type.Hidden
    CxfEditableField.FIELD_TYPE_BOOLEAN -> DSecret.Field.Type.Boolean
    else -> DSecret.Field.Type.Text
}

internal fun CxfEditableField.blankAsNull(): String? = value.takeIf { it.isNotBlank() }
