package com.artemchep.keyguard.common.service.imports.impl.onepassword

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportAttachmentSink
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.ImportAttachmentExtractor
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.decodeFromJsonElement
import com.artemchep.keyguard.common.service.imports.impl.decodeFromString
import com.artemchep.keyguard.common.service.imports.impl.extractArchiveEntries
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.impl.readUnencryptedArchive
import com.artemchep.keyguard.common.service.imports.impl.sshKeyOrNote
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedCard
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedUriOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.util.io.decodeTextDetectingEncoding
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 1Password's `.1pux` export: a ZIP archive with `export.data`, the JSON of
 * every account, vault and item, and the attached files as
 * `files/<document id>__<file name>`. Each vault becomes a folder; the
 * account does not.
 *
 * An item has a category, its login fields, and sections of typed fields; a
 * field value is an object whose single member names its type, e.g.
 * `{"concealed": "…"}` or `{"monthYear": 202612}`.
 *
 * Not registered on its own: [OnePasswordImporter] reads through it and
 * passes in its [format].
 */
internal class OnePasswordPuxImporter(
    override val format: ImportFormat,
    private val sshKeyImportService: SshKeyImportService,
) : Importer, ImportAttachmentExtractor {
    companion object {
        private const val DATA_ENTRY = "export.data"
        private const val FILES_PREFIX = "files/"

        private const val CATEGORY_LOGIN = "001"
        private const val CATEGORY_CARD = "002"
        private const val CATEGORY_NOTE = "003"
        private const val CATEGORY_IDENTITY = "004"
        private const val CATEGORY_PASSWORD = "005"
        private const val CATEGORY_SSH_KEY = "114"

        private const val DESIGNATION_USERNAME = "username"
        private const val DESIGNATION_PASSWORD = "password"
        private const val FIELD_TYPE_PASSWORD = "P"
        private const val FIELD_TYPE_CHECKBOX = "C"

        private const val STATE_ARCHIVED = "archived"
        private const val STATE_TRASHED = "trashed"
    }

    @Serializable
    private data class Export(
        val accounts: List<Account> = emptyList(),
    )

    @Serializable
    private data class Account(
        val vaults: List<Vault> = emptyList(),
    )

    @Serializable
    private data class Vault(
        val attrs: VaultAttrs? = null,
        val items: List<JsonElement> = emptyList(),
    )

    @Serializable
    private data class VaultAttrs(
        val name: String? = null,
    )

    @Serializable
    private data class Item(
        val categoryUuid: String? = null,
        val favIndex: Int? = null,
        val state: String? = null,
        val overview: Overview? = null,
        val details: Details? = null,
    )

    @Serializable
    private data class Overview(
        val title: String? = null,
        val url: String? = null,
        val urls: List<OverviewUrl> = emptyList(),
        val tags: List<String> = emptyList(),
    )

    @Serializable
    private data class OverviewUrl(
        val url: String? = null,
        val mode: String? = null,
    )

    @Serializable
    private data class Details(
        val loginFields: List<LoginField> = emptyList(),
        val notesPlain: String? = null,
        val password: String? = null,
        val sections: List<OnePasswordSection> = emptyList(),
        val passwordHistory: List<JsonElement> = emptyList(),
        val documentAttributes: OnePasswordFile? = null,
    )

    @Serializable
    private data class LoginField(
        val value: String? = null,
        val name: String? = null,
        val designation: String? = null,
        val fieldType: String? = null,
    )

    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult {
        val archive = input.readUnencryptedArchive { it == DATA_ENTRY }
        val data = archive.entries[DATA_ENTRY]
            ?: return ImportResult.Failure(ImportError.Parse)
        val text = decodeTextDetectingEncoding(data)
        val export = importJson.decodeFromString<Export>(text)
        val fileEntries = archive.namesByKey(
            prefix = FILES_PREFIX,
            delimiter = '_',
        )
        val plan = ImportPlanBuilder(format)
        export.accounts.forEach { account ->
            account.vaults.forEach { vault ->
                val folderKey = vault.attrs?.name
                    ?.let { listOf(it) }
                    ?.let(plan::folder)
                vault.items.forEach { element ->
                    val title = runCatchingNonFatal {
                        (element as? JsonObject)
                            ?.get("overview")
                            ?.let { importJson.decodeFromJsonElement<Overview>(it).title }
                    }.getOrNull()
                    plan.record(title) {
                        val item = importJson.decodeFromJsonElement<Item>(element)
                        mapItem(item, now, folderKey, fileEntries, plan)
                    }
                }
            }
        }
        return ImportResult.Success(plan.build())
    }

    override suspend fun <T> extract(
        input: ImportInput,
        password: Password?,
        refs: Set<String>,
        sink: ImportAttachmentSink<T>,
    ): Map<String, T> = input.extractArchiveEntries(password, refs, sink)

    private fun mapItem(
        item: Item,
        now: Instant,
        folderKey: String?,
        fileEntries: Map<String, String>,
        plan: ImportPlanBuilder,
    ) {
        val details = item.details ?: Details()
        val title = item.overview?.title
        val values = OnePasswordSectionValues(details.sections)

        plan.skip(ImportSkipReason.PasswordHistory, title, count = details.passwordHistory.size)
        val files = listOfNotNull(details.documentAttributes) + values.files
        val attachments = files.mapNotNull { file ->
            plannedAttachment(file, fileEntries)
                .also { if (it == null) plan.skip(ImportSkipReason.Attachment, title) }
        }

        val explicitUris = item.overview?.urls.orEmpty().mapNotNull { url ->
            importedUriOrNull(url.url)?.copy(
                match = when (url.mode) {
                    "host" -> DSecret.Uri.MatchType.Host
                    "never" -> DSecret.Uri.MatchType.Never
                    else -> null
                },
            )
        }
        // The primary URL has no mode; a matching URL entry takes precedence.
        val uris = (explicitUris + listOfNotNull(importedUriOrNull(item.overview?.url)))
            .distinctBy { it.uri }
        val meta = ImportedItemMeta(
            now = now,
            title = title,
            note = details.notesPlain,
            favorite = (item.favIndex ?: 0) > 0,
            uris = uris,
            tags = item.overview?.tags.orEmpty(),
            folderKey = folderKey,
            archived = item.state == STATE_ARCHIVED,
            trashed = item.state == STATE_TRASHED,
        )
        when (item.categoryUuid) {
            CATEGORY_LOGIN, CATEGORY_PASSWORD -> mapLogin(meta, attachments, details, values, plan)
            CATEGORY_CARD -> mapCard(meta, attachments, values, plan)
            CATEGORY_IDENTITY -> mapIdentity(meta, attachments, values, plan)
            CATEGORY_SSH_KEY -> mapSshKey(meta, attachments, values, plan)
            // A note keeps every field as a custom field.
            CATEGORY_NOTE -> plan.item(meta, DSecret.Type.SecureNote, values.fields(), attachments)
            // A category Keyguard has no type for: a note with every field.
            else -> plan.item(
                meta = meta,
                type = DSecret.Type.SecureNote,
                fields = listOfNotNull(importedFieldOrNull("Password", details.password, hidden = true)) +
                    values.fields(),
                attachments = attachments,
            )
        }
    }

    /**
     * The archive entry of a file, `files/<document id>_…`, from the
     * [fileEntries] keyed by document id; the separator has been seen with
     * two and with three underscores. `null` when the entry is missing or
     * over the upload limit.
     */
    private fun plannedAttachment(
        file: OnePasswordFile,
        fileEntries: Map<String, String>,
    ): ImportPlan.Attachment? {
        val documentId = file.documentId.orNullIfBlank()
        val ref = documentId
            ?.let(fileEntries::get)
            ?.takeIf { (file.decryptedSize ?: 0) <= KEEPASS_FILE_UPLOAD_MAX_BYTES }
        if (documentId == null || ref == null) {
            return null
        }
        return ImportPlan.Attachment(
            ref = ref,
            name = file.fileName.orNullIfBlank()
                ?: ref.removePrefix("$FILES_PREFIX${documentId}_").trimStart('_'),
            size = file.decryptedSize,
        )
    }

    private fun mapLogin(
        meta: ImportedItemMeta,
        attachments: List<ImportPlan.Attachment>,
        details: Details,
        values: OnePasswordSectionValues,
        plan: ImportPlanBuilder,
    ) {
        val username = details.loginFields
            .firstOrNull { it.designation == DESIGNATION_USERNAME }?.value
        val password = details.loginFields
            .firstOrNull { it.designation == DESIGNATION_PASSWORD }?.value
            ?: details.password
        // Fields of the sign-in form other than the username and password.
        val formFields = details.loginFields
            .filter { it.designation.isNullOrBlank() }
            .mapNotNull { field ->
                if (field.fieldType == FIELD_TYPE_CHECKBOX) {
                    // An empty checkbox is unchecked; any nonempty value is checked.
                    importedFieldOrNull(
                        name = field.name,
                        value = field.value?.isNotEmpty()?.toString(),
                        type = DSecret.Field.Type.Boolean,
                    )
                } else {
                    importedFieldOrNull(
                        name = field.name,
                        value = field.value,
                        hidden = field.fieldType == FIELD_TYPE_PASSWORD,
                    )
                }
            }
        val totp = values.takeTotp()
        plan.login(
            meta = meta,
            username = username,
            password = password,
            totp = totp,
            fields = formFields + values.fields(),
            attachments = attachments,
        )
    }

    private fun mapCard(
        meta: ImportedItemMeta,
        attachments: List<ImportPlan.Attachment>,
        values: OnePasswordSectionValues,
        plan: ImportPlanBuilder,
    ) {
        val rawExpiry = values.take("expiry")
        val rawValidFrom = values.take("validFrom")
        val card = importedCard(
            cardholderName = values.take("cardholder"),
            brand = values.take("type")?.let(::onePasswordCardBrand),
            number = values.take("ccnum"),
            code = values.take("cvv"),
            rawExpiry = rawExpiry,
            expiry = rawExpiry?.let(::onePasswordMonthYearOrNull),
            rawValidFrom = rawValidFrom,
            validFrom = rawValidFrom?.let(::onePasswordMonthYearOrNull),
        )
        plan.item(meta, DSecret.Type.Card, card.fields + values.fields(), attachments) {
            copy(card = card.card)
        }
    }

    private fun mapIdentity(
        meta: ImportedItemMeta,
        attachments: List<ImportPlan.Attachment>,
        values: OnePasswordSectionValues,
        plan: ImportPlanBuilder,
    ) {
        val address = values.takeAddress()
        val identity = CreateRequest.Identity(
            firstName = values.take("firstname"),
            middleName = values.take("initial"),
            lastName = values.take("lastname"),
            company = values.take("company"),
            email = values.take("email") ?: values.takeEmail(),
            phone = values.take("defphone") ?: values.take("cellphone"),
            username = values.take("username"),
            address1 = address?.street,
            city = address?.city,
            state = address?.state,
            postalCode = address?.zip,
            country = address?.country,
        )
        plan.item(meta, DSecret.Type.Identity, values.fields(), attachments) {
            copy(identity = identity)
        }
    }

    /**
     * 1Password keeps the key as PKCS#8; the vault stores the OpenSSH form.
     * The first copy of the key that converts wins. A key that does not
     * convert is counted, and the item keeps it as a note.
     */
    private fun mapSshKey(
        meta: ImportedItemMeta,
        attachments: List<ImportPlan.Attachment>,
        values: OnePasswordSectionValues,
        plan: ImportPlanBuilder,
    ) {
        val key = values.sshKey
        plan.sshKeyOrNote(
            meta = meta,
            privateKeys = key?.privateKeys.orEmpty(),
            publicKey = key?.publicKey,
            fingerprint = key?.fingerprint,
            sshKeyImportService = sshKeyImportService,
            fields = values.fields(),
            attachments = attachments,
        )
    }
}

private val onePasswordCardBrands = mapOf(
    "mc" to "Mastercard",
    "visa" to "Visa",
    "amex" to "American Express",
    "diners" to "Diners Club",
    "discover" to "Discover",
    "jcb" to "JCB",
    "maestro" to "Maestro",
    "unionpay" to "UnionPay",
)

/**
 * 1Password names a card brand by a short code, such as `mc`; Keyguard
 * matches brands by their full name.
 */
private fun onePasswordCardBrand(
    code: String,
): String = onePasswordCardBrands[code.trim().lowercase()] ?: code
