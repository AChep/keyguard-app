package com.artemchep.keyguard.common.service.export.impl

import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DOrganization
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.export.JsonExportService
import com.artemchep.keyguard.common.service.export.entity.CollectionExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemFieldExportEntity
import com.artemchep.keyguard.common.service.export.entity.FolderExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemCardExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemIdentityExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemLoginExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemLoginFido2CredentialsExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemLoginPasswordHistoryExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemLoginUriExportEntity
import com.artemchep.keyguard.common.service.export.entity.ItemSshKeyExportEntity
import com.artemchep.keyguard.common.service.export.entity.OrganizationExportEntity
import com.artemchep.keyguard.common.service.export.entity.RootExportEntity
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.provider.bitwarden.entity.CipherTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FieldTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.LinkedIdTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.UriMatchTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.of
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.kodein.di.DirectDI
import org.kodein.di.instance

class JsonExportServiceImpl(
    private val json: Json,
) : JsonExportService {
    constructor(
        directDI: DirectDI,
    ) : this(
        json = directDI.instance(),
    )

    override fun export(
        organizations: List<DOrganization>,
        collections: List<DCollection>,
        folders: List<DFolder>,
        ciphers: List<DSecret>,
    ): String {
        val localToRemoteFolderIdMap = folders
            .mapNotNull { folder ->
                val remoteId = folder.service.remote?.id
                    ?: return@mapNotNull null
                folder.id to remoteId
            }
            .toMap()

        val exportedOrganizations = organizations
            .map { organization ->
                OrganizationExportEntity(
                    id = organization.id,
                    name = organization.name,
                )
            }
        val exportedCollections = collections
            .map { collection ->
                CollectionExportEntity(
                    id = collection.id,
                    name = collection.name,
                    organizationId = collection.organizationId,
                    externalId = collection.externalId,
                )
            }
        val exportedFolders = folders
            .map { folder ->
                FolderExportEntity(
                    id = folder.service.remote?.id
                        ?: folder.id,
                    name = folder.name,
                )
            }
        val exportedItems = ciphers
            .map { cipher ->
                cipher.toExportEntity(
                    localToRemoteFolderIdMap = localToRemoteFolderIdMap,
                )
            }
        val entity = RootExportEntity(
            encrypted = false,
            organizations = exportedOrganizations,
            collections = exportedCollections,
            folders = exportedFolders,
            items = exportedItems,
        )
        return json.encodeToString(entity)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun DSecret.toExportEntity(
        localToRemoteFolderIdMap: Map<String, String>,
    ): JsonObject = buildJsonObject {
        val cipherId = service.remote?.id
            ?: id
        put("id", cipherId)
        // Map the local identifiers to their remote counterparts. This
        // ensures that the entries can be correctly restores later.
        val folderId = folderId
            ?.let { id ->
                localToRemoteFolderIdMap.getOrDefault(id, id)
            }
        put("folderId", folderId)
        // Organizations and collections.
        put("organizationId", organizationId)
        run {
            val key = "collectionIds"
            if (collectionIds.isNotEmpty()) {
                putJsonArray(key) {
                    collectionIds.forEach { collectionId ->
                        add(collectionId)
                    }
                }
            } else {
                put(key, null)
            }
        }

        //
        // Common
        //

        put("name", name)
        put("notes", notes)
        put("favorite", favorite)
        put("reprompt", reprompt.int)
        // Dates
        fun put(key: String, instant: Instant?) =
            put(
                key = key,
                element = instant?.let(json::encodeToJsonElement) ?: JsonNull,
            )
        put("revisionDate", revisionDate)
        put("creationDate", instant = createdDate ?: revisionDate)
        put("deletedDate", deletedDate)

        run {
            val key = "tags"
            if (tags.isNotEmpty()) {
                putJsonArray(key) {
                    tags.forEach { tag -> add(tag) }
                }
            }
        }

        run {
            val key = "attachments"
            // TODO: Export local attachments with their
            //  local identifier.
            val remoteAttachments = attachments
                .mapNotNull { it as? DSecret.Attachment.Remote }
            if (remoteAttachments.isNotEmpty()) {
                putJsonArray(key) {
                    remoteAttachments.forEach { attachment ->
                        val obj = buildJsonObject {
                            put("id", attachment.id)
                            put("size", attachment.size)
                            put("fileName", attachment.fileName)
                        }
                        add(obj)
                    }
                }
            }
        }

        //
        // Type
        //

        val itemType = CipherTypeEntity.of(type)
        if (itemType != null) {
            val value = json.encodeToJsonElement(itemType)
            put("type", value)
        }

        run {
            val key = "fields"
            val list = fields
                .map { field ->
                    val type = FieldTypeEntity.of(field.type)
                    val linkedId = field.linkedId?.let(LinkedIdTypeEntity::of)
                    ItemFieldExportEntity(
                        type = type,
                        name = field.name,
                        value = field.value,
                        linkedId = linkedId,
                    )
                }
            if (list.isNotEmpty()) {
                putJsonArray(
                    key = key,
                ) {
                    list.forEach { item ->
                        val el = json.encodeToJsonElement(item)
                        add(el)
                    }
                }
            } else {
                put(key, JsonNull)
            }
        }

        // Password history for historical reasons is not contained in the
        // login object. It also should always be in the json.
        run {
            val key = "passwordHistory"
            val list = login
                ?.passwordHistory
                ?.map { item ->
                    ItemLoginPasswordHistoryExportEntity(
                        lastUsedDate = item.lastUsedDate,
                        password = item.password,
                    )
                }
            if (!list.isNullOrEmpty()) {
                putJsonArray(
                    key = key,
                ) {
                    list.forEach { item ->
                        val el = json.encodeToJsonElement(item)
                        add(el)
                    }
                }
            } else {
                put(key, JsonNull)
            }
        }

        val urisEntity = uris
            .map { uri ->
                val match = uri.match?.let(UriMatchTypeEntity::of)
                ItemLoginUriExportEntity(
                    uri = uri.uri,
                    match = match,
                )
            }
        if (login != null) {
            val credentialsEntity = login.fido2Credentials
                .map { credential ->
                    ItemLoginFido2CredentialsExportEntity(
                        credentialId = credential.credentialId,
                        keyType = credential.keyType,
                        keyAlgorithm = credential.keyAlgorithm,
                        keyCurve = credential.keyCurve,
                        keyValue = credential.keyValue,
                        rpId = credential.rpId,
                        rpName = credential.rpName,
                        counter = credential.counter?.toString()
                            ?: "0",
                        userHandle = credential.userHandle,
                        userName = credential.userName,
                        userDisplayName = credential.userDisplayName,
                        discoverable = credential.discoverable.toString(),
                        creationDate = credential.creationDate,
                    )
                }
            val entity = ItemLoginExportEntity(
                uris = urisEntity,
                username = login.username,
                password = login.password,
                passwordRevisionDate = login.passwordRevisionDate,
                totp = login.totp?.raw,
                fido2Credentials = credentialsEntity,
            )
            val value = json.encodeToJsonElement(entity)
            put("login", value)
        } else if (urisEntity.isNotEmpty()) {
            // We still want to support exporting the URIs for our alternative
            // password services other than Bitwarden. We do this because in Keyguard
            // the URIs are stored outside of the Login object.
            val entity = ItemLoginExportEntity(
                uris = urisEntity,
            )
            val value = json.encodeToJsonElement(entity)
            put("login", value)
        }

        if (card != null) {
            val entity = ItemCardExportEntity(
                cardholderName = card.cardholderName,
                brand = card.brand,
                number = card.number,
                expMonth = card.expMonth,
                expYear = card.expYear,
                code = card.code,
            )
            val value = json.encodeToJsonElement(entity)
            put("card", value)
        }

        if (identity != null) {
            val entity = ItemIdentityExportEntity(
                title = identity.title,
                firstName = identity.firstName,
                middleName = identity.middleName,
                lastName = identity.lastName,
                address1 = identity.address1,
                address2 = identity.address2,
                address3 = identity.address3,
                city = identity.city,
                state = identity.state,
                postalCode = identity.postalCode,
                country = identity.country,
                company = identity.company,
                email = identity.email,
                phone = identity.phone,
                ssn = identity.ssn,
                username = identity.username,
                passportNumber = identity.passportNumber,
                licenseNumber = identity.licenseNumber,
            )
            val value = json.encodeToJsonElement(entity)
            put("identity", value)
        }

        if (identity != null) {
            val entity = ItemIdentityExportEntity(
                title = identity.title,
                firstName = identity.firstName,
                middleName = identity.middleName,
                lastName = identity.lastName,
                address1 = identity.address1,
                address2 = identity.address2,
                address3 = identity.address3,
                city = identity.city,
                state = identity.state,
                postalCode = identity.postalCode,
                country = identity.country,
                company = identity.company,
                email = identity.email,
                phone = identity.phone,
                ssn = identity.ssn,
                username = identity.username,
                passportNumber = identity.passportNumber,
                licenseNumber = identity.licenseNumber,
            )
            val value = json.encodeToJsonElement(entity)
            put("identity", value)
        }

        if (sshKey != null) {
            val entity = ItemSshKeyExportEntity(
                privateKey = sshKey.privateKey,
                publicKey = sshKey.publicKey,
                keyFingerprint = sshKey.fingerprint,
            )
            val value = json.encodeToJsonElement(entity)
            put("sshKey", value)
        }

        // secure note
        if (type == DSecret.Type.SecureNote) {
            val value = buildJsonObject {
                put("type", 0)
            }
            put("secureNote", value)
        }
    }
}
