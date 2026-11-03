package com.artemchep.keyguard.common.service.imports.impl.nordpass

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.CsvImporter
import com.artemchep.keyguard.common.service.imports.impl.CsvRow
import com.artemchep.keyguard.common.service.imports.impl.IMPORT_EXTENSION_CSV
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.decodeFromJsonElement
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.mapping.ImportedCard
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedCard
import com.artemchep.keyguard.common.service.imports.mapping.importedDateFromEpochSeconds
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonArray

/**
 * NordPass: one row per item or folder, told apart by the `type` column
 * (`password`, `note`, `credit_card`, `identity`, `document`, `folder`).
 * `additional_urls` and `custom_fields` hold JSON arrays. Older exports write
 * a folder row with an empty type.
 */
internal class NordPassCsvImporter : CsvImporter() {
    companion object {
        private const val TYPE_LOGIN = "password"
        private const val TYPE_NOTE = "note"
        private const val TYPE_CARD = "credit_card"
        private const val TYPE_IDENTITY = "identity"
        private const val TYPE_DOCUMENT = "document"
        private const val TYPE_FOLDER = "folder"

        private const val CUSTOM_TYPE_HIDDEN = "hidden"
        private const val CUSTOM_TYPE_DATE = "date"
    }

    override val format = ImportFormat(
        id = ImportFormatId.NordPass,
        title = "NordPass",
        extensions = listOf(IMPORT_EXTENSION_CSV),
    )

    override val requiredColumns = setOf("name", "url", "username", "password", "type")

    override fun titleOf(row: CsvRow): String? = row["name"].orNullIfBlank()

    override fun mapRow(
        row: CsvRow,
        now: Instant,
        plan: ImportPlanBuilder,
    ) {
        val title = titleOf(row)
        val folderKey = (row["folder"] ?: row["shared_folder"])
            ?.let { listOf(it) }
            ?.let(plan::folder)
        val fields = customFields(row["custom_fields"])
        val meta = ImportedItemMeta(
            now = now,
            title = title,
            note = row["note"],
            folderKey = folderKey,
        )
        when (row["type"]?.trim()?.lowercase()) {
            TYPE_LOGIN -> plan.login(
                meta = meta.copy(
                    uris = listOfNotNull(importedUriOrNull(row["url"])) + additionalUrls(row["additional_urls"]),
                ),
                username = row["username"] ?: row["email"],
                password = row["password"],
                totp = row["totp"],
                fields = fields,
            )

            // A document is a note with its details in custom fields.
            TYPE_NOTE, TYPE_DOCUMENT -> plan.item(meta, DSecret.Type.SecureNote, fields)

            TYPE_CARD -> {
                val card = card(row)
                val zip = importedFieldOrNull("ZIP code", row["zipcode"])
                plan.item(meta, DSecret.Type.Card, card.fields + listOfNotNull(zip) + fields) {
                    copy(card = card.card)
                }
            }

            TYPE_IDENTITY -> plan.item(meta, DSecret.Type.Identity, fields) {
                copy(identity = identity(row))
            }

            TYPE_FOLDER -> title?.let { plan.folder(listOf(it)) }

            else -> if (isFolderRow(row)) {
                title?.let { plan.folder(listOf(it)) }
            } else {
                plan.skip(ImportSkipReason.Item, title)
            }
        }
    }

    /** A row of nothing but a name, as older exports write a folder. */
    private fun isFolderRow(row: CsvRow) = row.record.fields.count { it.isNotBlank() } == 1 &&
        row["name"] != null

    private fun card(row: CsvRow): ImportedCard = importedCard(
        cardholderName = row["cardholdername"].orNullIfBlank(),
        number = row["cardnumber"].orNullIfBlank(),
        code = row["cvc"].orNullIfBlank(),
        rawExpiry = row["expirydate"],
        pin = row["pin"],
    )

    private fun identity(row: CsvRow): CreateRequest.Identity {
        val name = row["full_name"]?.trim().orEmpty()
        return CreateRequest.Identity(
            firstName = name.substringBeforeLast(' ').orNullIfBlank(),
            lastName = name.substringAfterLast(' ', missingDelimiterValue = "").orNullIfBlank(),
            email = row["email"].orNullIfBlank(),
            phone = row["phone_number"].orNullIfBlank(),
            address1 = row["address1"].orNullIfBlank(),
            address2 = row["address2"].orNullIfBlank(),
            city = row["city"].orNullIfBlank(),
            state = row["state"].orNullIfBlank(),
            postalCode = row["zipcode"].orNullIfBlank(),
            country = row["country"].orNullIfBlank(),
        )
    }

    private fun additionalUrls(
        raw: String?,
    ): List<DSecret.Uri> = raw
        ?.let { text ->
            runCatchingNonFatal {
                importJson.parseToJsonElement(text).jsonArray
                    .mapNotNull { element ->
                        importJson.decodeFromJsonElement<String>(element)
                            .let(::importedUriOrNull)
                    }
            }.getOrNull()
        }
        .orEmpty()

    @Serializable
    private data class CustomField(
        val label: String? = null,
        val type: String? = null,
        val value: String? = null,
    )

    /**
     * The custom fields, or the raw text as one field when it does not read
     * as the expected JSON, so nothing is lost. A date is stored as Unix
     * seconds.
     */
    private fun customFields(
        raw: String?,
    ): List<DSecret.Field> {
        val text = raw.orNullIfBlank()
            ?: return emptyList()
        val parsed = runCatchingNonFatal {
            importJson.parseToJsonElement(text).jsonArray
                .map { importJson.decodeFromJsonElement<CustomField>(it) }
        }.getOrNull()
        return parsed
            ?.mapNotNull { field ->
                val value = if (field.type == CUSTOM_TYPE_DATE) {
                    field.value?.trim()?.toLongOrNull()?.let(::importedDateFromEpochSeconds)
                        ?: field.value
                } else {
                    field.value
                }
                importedFieldOrNull(
                    name = field.label,
                    value = value,
                    hidden = field.type == CUSTOM_TYPE_HIDDEN,
                )
            }
            ?: listOfNotNull(importedFieldOrNull("Custom fields", text))
    }
}
