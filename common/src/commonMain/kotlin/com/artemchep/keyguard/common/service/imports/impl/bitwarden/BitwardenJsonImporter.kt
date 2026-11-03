package com.artemchep.keyguard.common.service.imports.impl.bitwarden

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.KEEPASS_FILE_UPLOAD_MAX_BYTES
import com.artemchep.keyguard.common.model.Password
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.CipherEncryptor
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.export.entity.ItemCardExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemIdentityExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemSshKeyExportEntity
import com.artemchep.keyguard.common.service.imports.ImportError
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportInput
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.ImportResult
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.Importer
import com.artemchep.keyguard.common.service.imports.impl.decodeFromJsonElement
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.impl.importedTotpOrCount
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.readText
import com.artemchep.keyguard.common.service.imports.mapping.IMPORT_DEFAULT_FIELD_LABEL
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedKeyguardTypeOrNull
import com.artemchep.keyguard.common.service.imports.mapping.importedLogin
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenTransportFields
import com.artemchep.keyguard.provider.bitwarden.crypto.SymmetricCryptoKey2
import com.artemchep.keyguard.provider.bitwarden.entity.FieldTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LinkedIdTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.UriMatchTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.domain
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import kotlin.time.Instant
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val TYPE_LOGIN = 1
private const val TYPE_SECURE_NOTE = 2
private const val TYPE_CARD = 3
private const val TYPE_IDENTITY = 4
private const val TYPE_SSH_KEY = 5

private const val REPROMPT_PASSWORD = 1

private const val FOLDER_DELIMITER = '/'

/**
 * Bitwarden's JSON export, plain or password-protected, and Keyguard's own
 * `vault.json`, which extends it with item types Bitwarden does not have.
 * Not registered on its own: [BitwardenImporter] and `KeyguardZipImporter`
 * read through it and pass in their [format].
 *
 * An account-restricted export is encrypted with a key only the Bitwarden
 * account holds, so it cannot be read here.
 */
internal class BitwardenJsonImporter(
    override val format: ImportFormat,
    private val cryptoGenerator: CryptoGenerator,
    private val cipherEncryptor: CipherEncryptor,
) : Importer {
    override fun parse(
        input: ImportInput,
        password: Password?,
        now: Instant,
    ): ImportResult = parseText(
        text = input.readText(),
        password = password,
        now = now,
        attachments = null,
    )

    /**
     * Parses a whole export. [attachments] lists the files that came along in
     * the same archive, keyed by the item and attachment id; without it an
     * item's attachments are counted as skipped.
     */
    fun parseText(
        text: String,
        password: Password?,
        now: Instant,
        attachments: BitwardenJsonAttachments?,
    ): ImportResult = when (val unlocked = unlock(parseRoot(text), password)) {
        is Unlocked.Vault -> {
            // Every export has the array, an empty vault's too, so an object
            // without one is some other JSON rather than an empty export.
            val items = unlocked.root["items"] as? JsonArray
            if (items != null) {
                ImportResult.Success(
                    plan = parseVault(unlocked.root, items, now, attachments),
                )
            } else {
                ImportResult.Failure(ImportError.Parse)
            }
        }

        is Unlocked.Failed -> ImportResult.Failure(unlocked.error)
    }

    private sealed interface Unlocked {
        data class Vault(val root: JsonObject) : Unlocked

        data class Failed(val error: ImportError) : Unlocked
    }

    private fun parseRoot(
        text: String,
    ): JsonObject? = runCatchingNonFatal {
        importJson.parseToJsonElement(text) as? JsonObject
    }.getOrNull()

    private fun unlock(
        root: JsonObject?,
        password: Password?,
    ): Unlocked = when {
        root == null -> Unlocked.Failed(ImportError.Parse)
        root.boolean("encrypted") != true -> Unlocked.Vault(root)
        // Encrypted with the Bitwarden account's own key.
        root.boolean("passwordProtected") != true -> Unlocked.Failed(ImportError.UnsupportedEncryption)
        password == null -> Unlocked.Failed(ImportError.PasswordRequired)
        else -> decryptPasswordProtected(root, password)
    }

    /**
     * A password-protected export derives a key from the password and the
     * file's own salt and KDF settings, stretches it, and encrypts the plain
     * export with it. A random value encrypted with the same key comes along
     * to tell a wrong password from a damaged file.
     */
    private fun decryptPasswordProtected(
        root: JsonObject,
        password: Password,
    ): Unlocked {
        val salt = root.string("salt")
        val validation = root.string("encKeyValidation_DO_NOT_EDIT")
        val data = root.string("data")
        val kdf = bitwardenExportKdfOrNull(
            type = root.int("kdfType"),
            iterations = root.int("kdfIterations"),
            memoryMb = root.int("kdfMemory"),
            parallelism = root.int("kdfParallelism"),
        )
        return when {
            salt == null || validation == null || data == null -> Unlocked.Failed(ImportError.Parse)
            kdf == null -> Unlocked.Failed(ImportError.UnsupportedEncryption)
            else -> {
                val key = deriveBitwardenExportKey(
                    cryptoGenerator = cryptoGenerator,
                    password = password.value,
                    salt = salt,
                    kdf = kdf,
                )
                decrypt(validation, data, key)
            }
        }
    }

    private fun decrypt(
        validation: String,
        data: String,
        key: SymmetricCryptoKey2,
    ): Unlocked {
        val validationOk = runCatchingNonFatal {
            cipherEncryptor.decode2(validation, key)
        }.isSuccess
        val inner = if (validationOk) {
            runCatchingNonFatal {
                cipherEncryptor.decode2(data, key).data.decodeToString()
            }.getOrNull()?.let(::parseRoot)
        } else {
            null
        }
        return when {
            !validationOk -> Unlocked.Failed(ImportError.WrongPassword)
            inner == null -> Unlocked.Failed(ImportError.Parse)
            inner.boolean("encrypted") == true -> Unlocked.Failed(ImportError.UnsupportedEncryption)
            else -> Unlocked.Vault(inner)
        }
    }

    private fun parseVault(
        root: JsonObject,
        items: JsonArray,
        now: Instant,
        attachments: BitwardenJsonAttachments?,
    ): ImportPlan {
        val plan = ImportPlanBuilder(format)
        val folderNameById = root.namesById("folders", plan)
        val collectionNameById = root.namesById("collections", plan)
        items.forEach { element ->
            val obj = element as? JsonObject
            val title = obj?.string("name")
            plan.record(title) {
                val item = importJson.decodeFromJsonElement<BitwardenJsonItem>(element)
                val folderName = item.folderId?.let(folderNameById::get)
                    ?: item.collectionIds
                        ?.firstNotNullOfOrNull(collectionNameById::get)
                val folderKey = folderName
                    ?.split(FOLDER_DELIMITER)
                    ?.let(plan::folder)
                mapItem(
                    item = item,
                    now = now,
                    folderKey = folderKey,
                    plan = plan,
                    attachments = attachments,
                )
            }
        }
        return plan.build()
    }

    /**
     * The names of a root array of `{id, name}` objects. An entry that cannot
     * be read is counted; its items still import, without a folder.
     */
    private fun JsonObject.namesById(
        key: String,
        plan: ImportPlanBuilder,
    ): Map<String, String> {
        val array = this[key] as? JsonArray
            ?: return emptyMap()
        return buildMap {
            array.forEach { element ->
                val obj = element as? JsonObject
                val id = obj?.string("id")
                val name = obj?.string("name")
                if (id != null && name != null) {
                    put(id, name)
                } else {
                    plan.skip(ImportSkipReason.Collection)
                }
            }
        }
    }

    private fun mapItem(
        item: BitwardenJsonItem,
        now: Instant,
        folderKey: String?,
        plan: ImportPlanBuilder,
        attachments: BitwardenJsonAttachments?,
    ) {
        val title = item.name
        val type = itemTypeOrNull(item.type)
        if (type == null) {
            plan.skip(ImportSkipReason.Item, title)
            return
        }
        // The passkeys of an item of another type are read once it is
        // known to import.
        val loginCredentials = if (type == BitwardenCipher.Type.Login) {
            passkeys(item, now, plan)
        } else {
            null
        }
        val transport = BitwardenTransportFields.decode(
            type = type,
            fields = item.fields
                .orEmpty()
                .mapNotNull { it.toBitwardenFieldOrNull() },
            credentials = loginCredentials,
        )
        // The links are dropped: they point at items of the source account,
        // which do not exist in the target one.
        val keyguard = importedKeyguardTypeOrNull(transport)
        if (keyguard == null) {
            plan.skip(ImportSkipReason.Environment, title)
            return
        }
        plan.skip(ImportSkipReason.EnvironmentReference, title, count = keyguard.clearedReferenceCount)
        item.passwordHistory
            ?.size
            ?.let { plan.skip(ImportSkipReason.PasswordHistory, title, count = it) }

        val credentials = transport.credentials
            ?: loginCredentials
            ?: passkeys(item, now, plan)
        plan.item(
            meta = item.importedMeta(
                now = now,
                tags = item.tags.orEmpty() + transport.tags,
                folderKey = folderKey,
            ),
            type = keyguard.type,
            fields = keyguard.fields.map(BitwardenCipher.Field::toDomain),
            attachments = plannedAttachments(item, plan, attachments),
        ) {
            copy(
                customIcon = transport.customIcon,
                fido2Credentials = credentials.map { it.toDomain() }.toPersistentList(),
                login = importedLogin(
                    username = item.login?.username,
                    password = item.login?.password,
                    totp = plan.importedTotpOrCount(item.login?.totp, title),
                ),
                card = item.card?.toCreateRequest() ?: CreateRequest.Card(),
                identity = item.identity?.toCreateRequest() ?: CreateRequest.Identity(),
                sshKey = item.sshKey.toCreateRequest(transport),
                gpgKey = keyguard.gpgKey,
                environment = keyguard.environment,
            )
        }
    }

    private fun passkeys(
        item: BitwardenJsonItem,
        now: Instant,
        plan: ImportPlanBuilder,
    ): List<BitwardenCipher.Login.Fido2Credentials> = item.login?.fido2Credentials
        .orEmpty()
        .mapNotNull { element ->
            val passkey = runCatchingNonFatal {
                importJson.decodeFromJsonElement<BitwardenJsonPasskey>(element)
                    .toStoredOrNull(now)
            }.getOrNull()
            if (passkey == null) {
                plan.skip(ImportSkipReason.Passkey, item.name)
            }
            passkey
        }

    /**
     * The attachments found in the archive the export came in. One that is
     * missing from it, over the upload limit, or exported without its file
     * is counted.
     */
    private fun plannedAttachments(
        item: BitwardenJsonItem,
        plan: ImportPlanBuilder,
        attachments: BitwardenJsonAttachments?,
    ): List<ImportPlan.Attachment> {
        val listed = item.attachments.orEmpty()
        val itemId = item.id
        if (attachments == null || itemId == null) {
            plan.skip(ImportSkipReason.Attachment, item.name, count = listed.size)
            return emptyList()
        }
        return listed.mapNotNull { attachment ->
            val size = attachment.size?.contentOrNull?.toLongOrNull()
            val planned = attachment.id
                // The vault does not stage a file over the upload limit.
                ?.takeIf { size == null || size <= KEEPASS_FILE_UPLOAD_MAX_BYTES }
                ?.let { attachments.find(itemId = itemId, attachmentId = it) }
                ?.let { ref ->
                    ImportPlan.Attachment(
                        ref = ref,
                        name = attachment.fileName.orNullIfBlank()
                            ?: ref.substringAfterLast('/'),
                        size = size,
                    )
                }
            if (planned == null) {
                plan.skip(ImportSkipReason.Attachment, item.name)
            }
            planned
        }
    }
}

private fun BitwardenJsonItem.importedMeta(
    now: Instant,
    tags: List<String>,
    folderKey: String?,
) = ImportedItemMeta(
    now = now,
    title = name,
    note = notes,
    favorite = favorite == true,
    reprompt = reprompt == REPROMPT_PASSWORD,
    uris = login?.uris.orEmpty()
        .mapNotNull { it.toStoredUriOrNull() }
        .map { it.toDomain().let { uri -> uri.copy(uri = uri.uri.trim()) } },
    tags = tags,
    folderKey = folderKey,
    archived = archivedDate.let { it != null && it !is JsonNull },
    trashed = deletedDate.let { it != null && it !is JsonNull },
)

private fun itemTypeOrNull(
    type: Int?,
): BitwardenCipher.Type? = when (type) {
    TYPE_LOGIN -> BitwardenCipher.Type.Login
    TYPE_SECURE_NOTE -> BitwardenCipher.Type.SecureNote
    TYPE_CARD -> BitwardenCipher.Type.Card
    TYPE_IDENTITY -> BitwardenCipher.Type.Identity
    TYPE_SSH_KEY -> BitwardenCipher.Type.SshKey
    else -> null
}

private fun ItemCardExportEntity.toCreateRequest() = CreateRequest.Card(
    cardholderName = cardholderName,
    brand = brand,
    number = number,
    expMonth = expMonth,
    expYear = expYear,
    code = code,
)

private fun ItemSshKeyExportEntity?.toCreateRequest(
    transport: BitwardenTransportFields.Decoded,
) = CreateRequest.SshKey(
    privateKey = this?.privateKey,
    publicKey = this?.publicKey,
    fingerprint = this?.keyFingerprint,
    certificate = transport.certificate,
    bookmarks = transport.bookmarks.map { bookmark ->
        DSecret.SshKey.Bookmark(name = bookmark.name, url = bookmark.url)
    }.toPersistentList(),
)

/**
 * Locates the file of an attachment inside the archive the export came in.
 */
internal fun interface BitwardenJsonAttachments {
    fun find(
        itemId: String,
        attachmentId: String,
    ): String?
}

@Serializable
private data class BitwardenJsonItem(
    val id: String? = null,
    val folderId: String? = null,
    val collectionIds: List<String>? = null,
    val type: Int? = null,
    val reprompt: Int? = null,
    val name: String? = null,
    val notes: String? = null,
    val favorite: Boolean? = null,
    val tags: List<String>? = null,
    val fields: List<BitwardenJsonField>? = null,
    val login: BitwardenJsonLogin? = null,
    val card: ItemCardExportEntity? = null,
    val identity: ItemIdentityExportEntity? = null,
    val sshKey: ItemSshKeyExportEntity? = null,
    val passwordHistory: List<JsonElement>? = null,
    val attachments: List<BitwardenJsonAttachment>? = null,
    val archivedDate: JsonElement? = null,
    val deletedDate: JsonElement? = null,
)

@Serializable
private data class BitwardenJsonField(
    val name: String? = null,
    val value: String? = null,
    val type: Int? = null,
    val linkedId: Int? = null,
)

@Serializable
private data class BitwardenJsonLogin(
    val uris: List<BitwardenJsonUri>? = null,
    val username: String? = null,
    val password: String? = null,
    val totp: String? = null,
    // Each passkey decodes on its own, so a damaged one is counted
    // instead of taking the whole login with it.
    val fido2Credentials: List<JsonElement>? = null,
)

@Serializable
private data class BitwardenJsonUri(
    val uri: String? = null,
    val match: Int? = null,
)

@Serializable
private data class BitwardenJsonPasskey(
    val credentialId: String? = null,
    val keyType: String? = null,
    val keyAlgorithm: String? = null,
    val keyCurve: String? = null,
    val keyValue: String? = null,
    val rpId: String? = null,
    val rpName: String? = null,
    val counter: String? = null,
    val userHandle: String? = null,
    val userName: String? = null,
    val userDisplayName: String? = null,
    val discoverable: String? = null,
    val creationDate: Instant? = null,
)

@Serializable
private data class BitwardenJsonAttachment(
    val id: String? = null,
    val fileName: String? = null,
    // Bitwarden writes the size as a string, Keyguard as a number.
    val size: JsonPrimitive? = null,
)

private fun BitwardenJsonField.toBitwardenFieldOrNull(): BitwardenCipher.Field? {
    val fieldType = FieldTypeEntity.entries
        .firstOrNull { it.int == type }
        ?.domain()
        ?: BitwardenCipher.Field.Type.Text
    val linked = linkedId?.let { id ->
        LinkedIdTypeEntity.entries
            .firstOrNull { it.int == id }
            ?.domain()
    }
    return when {
        // A linked field without a target means nothing.
        fieldType == BitwardenCipher.Field.Type.Linked && linked == null -> null
        name.isNullOrBlank() && value.isNullOrBlank() -> null
        else -> BitwardenCipher.Field(
            name = name.orNullIfBlank() ?: IMPORT_DEFAULT_FIELD_LABEL,
            value = value,
            type = fieldType,
            linkedId = linked,
        )
    }
}

private fun BitwardenJsonUri.toStoredUriOrNull(): BitwardenCipher.Login.Uri? {
    val value = uri.orNullIfBlank()
        ?: return null
    val matchType = match?.let { m ->
        UriMatchTypeEntity.entries
            .firstOrNull { it.int == m }
            ?.domain()
    }
    return BitwardenCipher.Login.Uri(
        uri = value,
        match = matchType,
    )
}

private fun BitwardenJsonPasskey.toStoredOrNull(
    now: Instant,
): BitwardenCipher.Login.Fido2Credentials? {
    val credentialId = credentialId.orNullIfBlank()
    val keyValue = keyValue.orNullIfBlank()
    val rpId = rpId.orNullIfBlank()
    if (credentialId == null || keyValue == null || rpId == null) {
        return null
    }
    return BitwardenCipher.Login.Fido2Credentials(
        credentialId = credentialId,
        keyType = keyType.orNullIfBlank() ?: "public-key",
        keyAlgorithm = keyAlgorithm.orNullIfBlank() ?: "ECDSA",
        keyCurve = keyCurve.orNullIfBlank() ?: "P-256",
        keyValue = keyValue,
        rpId = rpId,
        rpName = rpName,
        counter = counter?.toIntOrNull()?.toString().orEmpty(),
        userHandle = userHandle,
        userName = userName,
        userDisplayName = userDisplayName,
        discoverable = (discoverable?.toBooleanStrictOrNull() ?: true).toString(),
        creationDate = creationDate ?: now,
    )
}

private fun ItemIdentityExportEntity.toCreateRequest() = CreateRequest.Identity(
    title = title,
    firstName = firstName,
    middleName = middleName,
    lastName = lastName,
    address1 = address1,
    address2 = address2,
    address3 = address3,
    city = city,
    state = state,
    postalCode = postalCode,
    country = country,
    company = company,
    email = email,
    phone = phone,
    ssn = ssn,
    username = username,
    passportNumber = passportNumber,
    licenseNumber = licenseNumber,
)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
