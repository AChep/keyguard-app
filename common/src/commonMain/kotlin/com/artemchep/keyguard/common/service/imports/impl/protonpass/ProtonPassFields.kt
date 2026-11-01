package com.artemchep.keyguard.common.service.imports.impl.protonpass

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.imports.impl.decodeFromJsonElement
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val FIELD_TYPE_HIDDEN = "hidden"
private const val FIELD_TYPE_TIMESTAMP = "timestamp"

private const val SECTIONS = "sections"

/** The type of a custom field that holds a one-time password. */
internal const val PROTON_FIELD_TYPE_TOTP = "totp"

/**
 * The autofill modes of a login address, mapped to Keyguard's match types.
 * The default mode, and the wildcard pattern that has no counterpart, keep
 * the default match.
 */
private val urlMatchByMode = mapOf(
    1 to DSecret.Uri.MatchType.Host,
    2 to DSecret.Uri.MatchType.Never,
    3 to DSecret.Uri.MatchType.StartsWith,
    5 to DSecret.Uri.MatchType.RegularExpression,
    6 to DSecret.Uri.MatchType.Exact,
)

@Serializable
private data class Section(
    val sectionName: String? = null,
    val sectionFields: List<ProtonPassExtraField> = emptyList(),
)

@Serializable
private data class AutofillUrl(
    val url: String? = null,
    val mode: Int? = null,
)

/**
 * The login's addresses. `autofillUrls` carries every address with its
 * autofill mode; older exports have only `urls`.
 */
internal fun JsonObject.protonUris(): List<DSecret.Uri> {
    val autofill = (this["autofillUrls"] as? JsonArray)
        .orEmpty()
        .mapNotNull { element ->
            val url = importJson.decodeFromJsonElement<AutofillUrl>(element)
            importedUriOrNull(url.url)
                ?.copy(match = url.mode?.let(urlMatchByMode::get))
        }
    return autofill.ifEmpty {
        (this["urls"] as? JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::importedUriOrNull) }
    }
}

internal fun ProtonPassExtraField.protonValue(): String? = when (type) {
    PROTON_FIELD_TYPE_TOTP -> data?.totpUri ?: data?.content
    FIELD_TYPE_TIMESTAMP -> data?.timestamp ?: data?.content
    else -> data?.content
}

internal fun List<ProtonPassExtraField>.protonFields(): List<DSecret.Field> = mapNotNull { field ->
    importedFieldOrNull(
        name = field.fieldName,
        value = field.protonValue(),
        hidden = field.type == FIELD_TYPE_HIDDEN || field.type == PROTON_FIELD_TYPE_TOTP,
    )
}

internal fun JsonObject.protonExtraFieldsOf(
    key: String,
): List<ProtonPassExtraField> = (this[key] as? JsonArray)
    .orEmpty()
    .map { importJson.decodeFromJsonElement<ProtonPassExtraField>(it) }

/**
 * The fields of every section; a section name prefixes its fields' labels.
 */
internal fun JsonObject.protonSectionFields(): List<DSecret.Field> =
    (this[SECTIONS] as? JsonArray).orEmpty().protonSectionsToFields()

internal fun List<JsonElement>.protonSectionsToFields(): List<DSecret.Field> = flatMap { element ->
    val section = importJson.decodeFromJsonElement<Section>(element)
    val prefix = section.sectionName.orNullIfBlank()
    section.sectionFields
        .map { field ->
            if (prefix != null && field.fieldName != null) {
                field.copy(fieldName = "$prefix: ${field.fieldName}")
            } else {
                field
            }
        }
        .protonFields()
}

internal fun JsonObject.protonText(key: String): String? =
    protonRawText(key).orNullIfBlank()

/** Passwords may consist entirely of whitespace. */
internal fun JsonObject.protonRawText(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.protonInt(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

/**
 * A text member not read elsewhere, as a custom field.
 */
internal fun protonTextFieldOrNull(
    key: String,
    value: JsonElement,
): DSecret.Field? {
    val text = (value as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.contentOrNull
    return importedFieldOrNull(
        name = key,
        value = text,
    )
}

/**
 * A login's username and the fields that keep the rest of its names.
 */
internal class ProtonPassUsername(
    val username: String?,
    val fields: List<DSecret.Field>,
)

/**
 * Proton Pass keeps a login's username and email apart; the vault has one
 * username. The username wins and the email is kept as a field; without a
 * username, the email, or else the address of the item's alias, is the
 * username.
 */
internal fun protonPassUsername(
    username: String?,
    email: String?,
    aliasEmail: String? = null,
): ProtonPassUsername = ProtonPassUsername(
    username = username ?: email ?: aliasEmail,
    fields = listOfNotNull(
        importedFieldOrNull("Email", email.takeIf { username != null }),
    ),
)
