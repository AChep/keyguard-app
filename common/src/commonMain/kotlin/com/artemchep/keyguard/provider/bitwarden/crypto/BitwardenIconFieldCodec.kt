package com.artemchep.keyguard.provider.bitwarden.crypto

import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon

/** Shares the icon transport and collision rules between sync and file transfers. */
internal object BitwardenIconFieldCodec {
    const val FIELD_NAME = "Custom Icon Name"

    fun encode(
        customIcon: KeePassIcon?,
        fields: List<BitwardenCipher.Field>,
    ): List<BitwardenCipher.Field> {
        val icon = customIcon
            ?.takeUnless { it == KeePassIcon.Key }
            ?: return fields

        val reservedNames = fields
            .asSequence()
            .mapNotNull { it.name }
            .filter { it != FIELD_NAME }
            .toMutableSet()
        reservedNames += FIELD_NAME

        fun nextAvailableIconFieldName(): String {
            var index = 1
            while (true) {
                val name = "$FIELD_NAME #$index"
                if (reservedNames.add(name)) return name
                index++
            }
        }

        val iconField = BitwardenCipher.Field(
            name = FIELD_NAME,
            value = icon.name,
            type = BitwardenCipher.Field.Type.Text,
        )
        val renamedFields = fields.map { field ->
            if (field.name == FIELD_NAME) {
                field.copy(name = nextAvailableIconFieldName())
            } else {
                field
            }
        }
        return listOf(iconField) + renamedFields
    }

    /** The icon of the [field], or `null` if it is not an intact icon field. */
    fun decodeField(field: BitwardenCipher.Field): KeePassIcon? {
        if (
            field.name != FIELD_NAME ||
            field.type != BitwardenCipher.Field.Type.Text ||
            field.linkedId != null
        ) return null
        return KeePassIcon.entries.firstOrNull { it.name == field.value }
    }
}
