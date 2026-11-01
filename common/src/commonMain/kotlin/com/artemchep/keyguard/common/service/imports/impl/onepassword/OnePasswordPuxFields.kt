package com.artemchep.keyguard.common.service.imports.impl.onepassword

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.imports.impl.decodeFromJsonElement
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.mapping.ImportFieldBag
import com.artemchep.keyguard.common.service.imports.mapping.ImportedExpiry
import com.artemchep.keyguard.common.service.imports.mapping.importedDateFromEpochSeconds
import com.artemchep.keyguard.common.service.imports.mapping.importedExpiryOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.common.service.imports.mapping.toFieldText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val KIND_TOTP = "totp"
private const val KIND_FILE = "file"
private const val KIND_SSH_KEY = "sshKey"
private const val KIND_ADDRESS = "address"
private const val KIND_EMAIL = "email"
private const val KIND_DATE = "date"
private const val KIND_MONTH_YEAR = "monthYear"
private const val KIND_CONCEALED = "concealed"

private const val MONTH_YEAR_DIVISOR = 100

@Serializable
internal data class OnePasswordSection(
    val title: String? = null,
    val fields: List<OnePasswordSectionField> = emptyList(),
)

@Serializable
internal data class OnePasswordSectionField(
    val title: String? = null,
    val id: String? = null,
    val value: JsonObject? = null,
)

/**
 * An attached file: a document item's own file, or a file field.
 */
@Serializable
internal data class OnePasswordFile(
    val fileName: String? = null,
    val documentId: String? = null,
    val decryptedSize: Long? = null,
)

@Serializable
internal data class OnePasswordAddress(
    val street: String? = null,
    val city: String? = null,
    val state: String? = null,
    val zip: String? = null,
    val country: String? = null,
) {
    fun toText(): String? = listOf(street, city, state, zip, country)
        .mapNotNull { it.orNullIfBlank() }
        .joinToString(", ")
        .orNullIfBlank()
}

@Serializable
private data class OnePasswordSshKeyValue(
    val privateKey: String? = null,
    val metadata: Metadata? = null,
) {
    @Serializable
    data class Metadata(
        val privateKey: String? = null,
        val publicKey: String? = null,
        val fingerprint: String? = null,
    )
}

internal data class OnePasswordSshKey(
    /** Every copy of the private key the export carries, preferred first. */
    val privateKeys: List<String>,
    val publicKey: String?,
    val fingerprint: String?,
)

/**
 * The section fields of one item. Fields read into a typed member are taken
 * out; the rest become custom fields.
 */
internal class OnePasswordSectionValues(
    sections: List<OnePasswordSection>,
) {
    private class Value(
        val id: String?,
        val title: String?,
        val kind: String,
        val text: String?,
        val address: OnePasswordAddress? = null,
    )

    var sshKey: OnePasswordSshKey? = null
        private set

    val files = mutableListOf<OnePasswordFile>()

    private val values = ImportFieldBag(
        values = sections.flatMap { section ->
            section.fields.mapNotNull { field ->
                read(field, sectionTitle = section.title)
            }
        },
        keyOf = { it.id },
    )

    /**
     * The value of a field, or `null` for a file or an SSH key, which are
     * kept apart.
     */
    private fun read(
        field: OnePasswordSectionField,
        sectionTitle: String?,
    ): Value? {
        val (kind, content) = field.value?.entries?.firstOrNull()
            ?: return null
        // Untitled fields are common; the section names them best.
        val title = field.title.orNullIfBlank() ?: sectionTitle.orNullIfBlank()
        return when (kind) {
            KIND_FILE -> {
                files += importJson.decodeFromJsonElement<OnePasswordFile>(content)
                null
            }

            KIND_SSH_KEY -> {
                val value = importJson.decodeFromJsonElement<OnePasswordSshKeyValue>(content)
                sshKey = OnePasswordSshKey(
                    privateKeys = listOfNotNull(value.metadata?.privateKey, value.privateKey)
                        .filter { it.isNotBlank() }
                        .distinct(),
                    publicKey = value.metadata?.publicKey,
                    fingerprint = value.metadata?.fingerprint,
                )
                null
            }

            KIND_ADDRESS -> Value(
                id = field.id,
                title = title,
                kind = kind,
                text = null,
                address = importJson.decodeFromJsonElement<OnePasswordAddress>(content),
            )

            KIND_EMAIL -> Value(
                id = field.id,
                title = title,
                kind = kind,
                text = ((content as? JsonObject)?.get("email_address") as? JsonPrimitive)?.contentOrNull
                    ?: (content as? JsonPrimitive)?.contentOrNull,
            )

            else -> Value(field.id, title, kind, (content as? JsonPrimitive)?.contentOrNull)
        }
    }

    /** Takes the text of the field with [id] out of the custom fields. */
    fun take(id: String): String? = takeText { it.id == id }

    /** Takes the first non-blank one-time password; the rest stay fields. */
    fun takeTotp(): String? = takeText { it.kind == KIND_TOTP }

    /** Takes the first non-blank typed email; the rest stay fields. */
    fun takeEmail(): String? = takeText { it.kind == KIND_EMAIL }

    private fun takeText(predicate: (Value) -> Boolean): String? = values
        .takeFirst { predicate(it) && !it.text.isNullOrBlank() }
        ?.text
        .orNullIfBlank()

    /** Takes the first address field. */
    fun takeAddress(): OnePasswordAddress? = values.takeFirst { it.address != null }?.address

    fun fields(): List<DSecret.Field> = values.rest { value ->
        importedFieldOrNull(
            name = value.title,
            value = value.displayText(),
            hidden = value.kind == KIND_CONCEALED || value.kind == KIND_TOTP,
        )
    }

    private fun Value.displayText(): String? = when (kind) {
        KIND_ADDRESS -> address?.toText()
        KIND_DATE -> text?.toLongOrNull()?.let(::importedDateFromEpochSeconds)
        KIND_MONTH_YEAR -> text?.let(::onePasswordMonthYearOrNull)?.toFieldText() ?: text
        // Whitespace can be a password, but cannot be an OTP configuration.
        KIND_TOTP -> text.orNullIfBlank()
        else -> text
    }
}

/** `202612` is December 2026. */
internal fun onePasswordMonthYearOrNull(
    raw: String,
): ImportedExpiry? = raw.trim().toIntOrNull()
    ?.let { value ->
        importedExpiryOrNull(
            month = value % MONTH_YEAR_DIVISOR,
            year = value / MONTH_YEAR_DIVISOR,
        )
    }
