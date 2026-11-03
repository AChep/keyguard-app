package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank

/**
 * Maps imported custom fields into Keyguard fields. The inverse of
 * [mapCustomFields]; field kinds without a Keyguard counterpart become text.
 */
internal fun mapImportCustomFields(
    fields: List<CxfEditableField>,
): List<DSecret.Field> = fields.mapNotNull { field ->
    field.toFieldOrNull(fallbackLabel = null)
}

/**
 * The field under its own label, or [fallbackLabel] if it has none, see
 * [importedFieldOrNull].
 */
internal fun CxfEditableField.toFieldOrNull(
    fallbackLabel: String?,
): DSecret.Field? = importedFieldOrNull(
    name = label.orNullIfBlank() ?: fallbackLabel,
    value = value,
    type = fieldTypeToFieldType(),
)

internal fun CxfEditableField.fieldTypeToFieldType(): DSecret.Field.Type = when (fieldType) {
    CxfEditableField.FIELD_TYPE_CONCEALED_STRING -> DSecret.Field.Type.Hidden
    CxfEditableField.FIELD_TYPE_BOOLEAN -> DSecret.Field.Type.Boolean
    else -> DSecret.Field.Type.Text
}
