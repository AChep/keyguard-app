package com.artemchep.keyguard.common.service.imports.impl.enpass

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.decodeFromJsonElement
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.impl.importedFolderPath
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.impl.readText
import com.artemchep.keyguard.common.service.imports.mapping.ImportFieldBag
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedCard
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Enpass: a JSON file of `folders`, a tree by `parent_uuid`, and `items`.
 * An item's values are a list of typed `fields`; the field types decide
 * what becomes the username, password, addresses and card members. An item
 * becomes a login only if it is one, or holds a password; anything else is
 * a note with its values as custom fields.
 */
internal class EnpassJsonImporter : Importer {
    companion object {
        private const val CATEGORY_CARD = "creditcard"
        private val loginCategories = setOf("login", "password")

        private const val FIELD_USERNAME = "username"
        private const val FIELD_EMAIL = "email"
        private const val FIELD_PASSWORD = "password"
        private const val FIELD_URL = "url"
        private const val FIELD_TOTP = "totp"
        private const val FIELD_SECTION = "section"
        private const val FIELD_CARD_HOLDER = "ccName"
        private const val FIELD_CARD_NUMBER = "ccNumber"
        private const val FIELD_CARD_CODE = "ccCvc"
        private const val FIELD_CARD_EXPIRY = "ccExpiry"
        private const val FIELD_CARD_TYPE = "ccType"
        private const val FIELD_CARD_VALID_FROM = "ccValidfrom"
        private const val FIELD_ANDROID_APP = ".Android#"
    }

    override val format = ImportFormat(
        id = ImportFormatId.Enpass,
        title = "Enpass",
        extensions = listOf("json"),
    )

    @Serializable
    private data class Folder(
        val uuid: String? = null,
        val title: String? = null,
        @SerialName("parent_uuid")
        val parentUuid: String? = null,
    )

    @Serializable
    private data class Item(
        val title: String? = null,
        val note: String? = null,
        val category: String? = null,
        @SerialName("template_type")
        val templateType: String? = null,
        val favorite: Int? = null,
        val trashed: Int? = null,
        val archived: Int? = null,
        val folders: List<String> = emptyList(),
        val fields: List<Field> = emptyList(),
        val attachments: List<JsonElement> = emptyList(),
    )

    @Serializable
    private data class Field(
        val label: String? = null,
        val type: String? = null,
        val value: String? = null,
        val sensitive: Int? = null,
        val deleted: Int? = null,
        val history: List<JsonElement> = emptyList(),
    )

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult {
        val root = importJson.parseToJsonElement(input.readText()) as? JsonObject
        val items = root?.get("items") as? JsonArray
        if (root == null || items == null) {
            return ImportResult.Failure(ImportError.Parse)
        }
        val plan = ImportPlanBuilder(format)
        val folders = (root["folders"] as? JsonArray)
            .orEmpty()
            .mapNotNull { element ->
                runCatchingNonFatal { importJson.decodeFromJsonElement<Folder>(element) }
                    .getOrNull()
                    ?.takeIf { it.uuid != null }
                    ?: run {
                        plan.skip(ImportSkipReason.Collection)
                        null
                    }
            }
            .associateBy { it.uuid!! }
        items.forEach { element ->
            val title = runCatchingNonFatal {
                importJson.decodeFromJsonElement<Item>(element).title
            }.getOrNull()
            plan.record(title) {
                val item = importJson.decodeFromJsonElement<Item>(element)
                val folderKey = item.folders.firstOrNull()
                    ?.let { id ->
                        importedFolderPath(
                            id = id,
                            parentOf = { folders[it]?.parentUuid },
                            titleOf = { folders[it]?.title },
                        )
                    }
                    ?.let(plan::folder)
                // An item can be in more folders; a folder holds one, so
                // the others become tags.
                val tags = item.folders.drop(1).mapNotNull { folders[it]?.title }
                mapItem(item, now, folderKey, tags, plan)
            }
        }
        return ImportResult.Success(plan.build())
    }

    private fun mapItem(
        item: Item,
        now: Instant,
        folderKey: String?,
        tags: List<String>,
        plan: ImportPlanBuilder,
    ) {
        val meta = ImportedItemMeta(
            now = now,
            title = item.title,
            note = item.note,
            favorite = item.favorite == 1,
            tags = tags,
            folderKey = folderKey,
            archived = item.archived == 1,
            trashed = item.trashed == 1,
        )
        // Those read into a typed member are taken out, the rest become
        // custom fields.
        val fields = ImportFieldBag(
            values = item.fields.filter { it.deleted != 1 && it.type != FIELD_SECTION && !it.value.isNullOrEmpty() },
            keyOf = { it.type },
        )
        plan.skip(ImportSkipReason.Attachment, item.title, count = item.attachments.size)
        plan.skip(ImportSkipReason.PasswordHistory, item.title, count = item.fields.sumOf { it.history.size })
        // Android app addresses, as a bare package or an `android://` URI.
        val apps = fields.takeAll(FIELD_ANDROID_APP).mapNotNull { field ->
            val value = field.value.orEmpty()
            val uri = if ("://" in value) value else "$PROTOCOL_ANDROID_APP$value"
            importedUriOrNull(uri)
        }
        val isLogin = item.category in loginCategories ||
            loginCategories.any { item.templateType?.startsWith("$it.") == true } ||
            fields.has(FIELD_PASSWORD)
        when {
            item.category == CATEGORY_CARD -> {
                val card = importedCard(
                    rawExpiry = fields.take(FIELD_CARD_EXPIRY)?.value,
                    rawValidFrom = fields.take(FIELD_CARD_VALID_FROM)?.value,
                    cardholderName = fields.take(FIELD_CARD_HOLDER)?.value.orNullIfBlank(),
                    number = fields.take(FIELD_CARD_NUMBER)?.value.orNullIfBlank(),
                    code = fields.take(FIELD_CARD_CODE)?.value.orNullIfBlank(),
                    brand = fields.take(FIELD_CARD_TYPE)?.value.orNullIfBlank(),
                )
                plan.item(meta, DSecret.Type.Card, card.fields + fields.customFields()) {
                    copy(card = card.card)
                }
            }

            isLogin -> plan.login(
                meta = meta.copy(
                    uris = fields.takeAll(FIELD_URL).mapNotNull { importedUriOrNull(it.value) } + apps,
                ),
                username = fields.take(FIELD_USERNAME)?.value ?: fields.take(FIELD_EMAIL)?.value,
                password = fields.take(FIELD_PASSWORD)?.value,
                totp = fields.take(FIELD_TOTP)?.value,
                fields = fields.customFields(),
            )

            // A note, an identity, or any other category: every value is kept
            // as a custom field.
            else -> plan.item(meta, DSecret.Type.SecureNote, fields.customFields())
        }
    }

    private fun ImportFieldBag<Field>.customFields() = rest { field ->
        importedFieldOrNull(field.label, field.value, hidden = field.sensitive == 1)
    }
}
