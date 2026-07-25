package com.artemchep.keyguard.common.service.cipherlink

/**
 * Names of the custom fields that carry the cipher links. A link is
 * encoded as a plain text field named [FIELD_NAME_PREFIX] followed by a
 * one-based index, holding a [CipherLink] uri as its value.
 */
object CipherLinkFields {
    const val FIELD_NAME_PREFIX = "keyguard.link."

    data class ParsedField(
        val index: Int,
        val link: CipherLink,
    )

    /**
     * Returns the name of a custom field that holds the link at the
     * given one-based [index].
     */
    fun fieldName(index: Int): String = "$FIELD_NAME_PREFIX$index"

    /**
     * Formats the links into the reserved custom fields, in order, as the
     * name to value pairs. Invalid and duplicate links are skipped, so the
     * written indexes stay contiguous and one-based.
     */
    fun format(remoteCipherIds: List<String>): List<Pair<String, String>> =
        canonicalizeCipherLinks(remoteCipherIds)
        .mapIndexed { index, link ->
            fieldName(index + 1) to link.toString()
        }

    /**
     * Returns the one-based index of a link custom field, or `null` if the
     * name does not belong to a link field.
     */
    fun parseFieldIndex(name: String?): Int? = name
        ?.takeIf { it.startsWith(FIELD_NAME_PREFIX) }
        ?.substring(FIELD_NAME_PREFIX.length)
        // Guard the digits ourselves, otherwise we would also accept the
        // signs and the whitespace that toIntOrNull tolerates.
        ?.takeIf { raw -> raw.isNotEmpty() && raw.all { it in '0'..'9' } }
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

    /**
     * Parses a reserved custom-field name and value as a cipher link.
     */
    fun parse(
        name: String?,
        value: String?,
    ): ParsedField? {
        val index = parseFieldIndex(name)
            ?: return null
        val link = CipherLink.parse(value)
            ?: return null
        return ParsedField(
            index = index,
            link = link,
        )
    }

    /**
     * Orders parsed fields by their ordinal and removes duplicate canonical
     * targets while preserving the first field in that order.
     */
    fun decode(
        fields: Iterable<ParsedField>,
    ): List<CipherLink> = fields
        .sortedBy(ParsedField::index)
        .distinctBy { field -> field.link.remoteCipherId }
        .map(ParsedField::link)
}
