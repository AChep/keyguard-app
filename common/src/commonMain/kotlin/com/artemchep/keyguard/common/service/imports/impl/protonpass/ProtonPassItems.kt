package com.artemchep.keyguard.common.service.imports.impl.protonpass

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.login
import com.artemchep.keyguard.common.service.imports.impl.sshKeyOrNote
import com.artemchep.keyguard.common.service.imports.mapping.ImportFieldBag
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.importedCard
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val STATE_TRASHED = 2

private const val TYPE_LOGIN = "login"
private const val TYPE_ALIAS = "alias"
private const val TYPE_CARD = "creditCard"
private const val TYPE_IDENTITY = "identity"
private const val TYPE_SSH_KEY = "sshKey"
private const val TYPE_WIFI = "wifi"

private val cardBrandByType = mapOf(
    2 to "Visa",
    3 to "Mastercard",
    4 to "American Express",
)

private val wifiSecurityByType = mapOf(
    1 to "WPA",
    2 to "WPA2",
    3 to "WPA3",
    4 to "WEP",
)

/** Identity members that hold arrays of extra fields. */
private val identityExtraMembers = listOf(
    "extraPersonalDetails",
    "extraAddressDetails",
    "extraContactDetails",
    "extraWorkDetails",
)

@Serializable
internal data class ProtonPassItem(
    val folderId: String? = null,
    val data: Data? = null,
    val state: Int? = null,
    val aliasEmail: String? = null,
    val pinned: Boolean? = null,
    val files: List<JsonElement> = emptyList(),
) {
    @Serializable
    data class Data(
        val metadata: Metadata? = null,
        val extraFields: List<ProtonPassExtraField> = emptyList(),
        val type: String? = null,
        val content: JsonObject? = null,
    )

    @Serializable
    data class Metadata(
        val name: String? = null,
        val note: String? = null,
    )
}

/**
 * A custom field. Its value sits in a member named after its type: text and
 * hidden fields in `content`, one-time passwords in `totpUri`, dates in
 * `timestamp`.
 */
@Serializable
internal data class ProtonPassExtraField(
    val fieldName: String? = null,
    val type: String? = null,
    val data: ProtonPassExtraFieldData? = null,
)

@Serializable
internal data class ProtonPassExtraFieldData(
    val content: String? = null,
    val totpUri: String? = null,
    val timestamp: String? = null,
)

/**
 * What every item type is mapped from, besides its [meta].
 */
private class ProtonPassContent(
    val content: JsonObject,
    val extraFields: List<ProtonPassExtraField>,
)

internal fun mapProtonPassItem(
    item: ProtonPassItem,
    now: Instant,
    folderKey: String?,
    plan: ImportPlanBuilder,
    sshKeyImportService: SshKeyImportService,
) {
    val data = item.data ?: ProtonPassItem.Data()
    val meta = ImportedItemMeta(
        now = now,
        title = data.metadata?.name,
        note = data.metadata?.note,
        favorite = item.pinned == true,
        folderKey = folderKey,
        trashed = item.state == STATE_TRASHED,
    )
    val source = ProtonPassContent(
        content = data.content ?: JsonObject(emptyMap()),
        extraFields = data.extraFields,
    )
    plan.skip(ImportSkipReason.Attachment, meta.title, count = item.files.size)
    when (data.type) {
        TYPE_LOGIN, TYPE_ALIAS -> mapLogin(meta, source, item.aliasEmail, plan)
        TYPE_CARD -> mapCard(meta, source, plan)
        TYPE_IDENTITY -> mapIdentity(meta, source, plan)
        TYPE_SSH_KEY -> mapSshKey(meta, source, plan, sshKeyImportService)
        TYPE_WIFI -> mapWifi(meta, source, plan)
        // A note, or a custom item whose sections become custom fields.
        else -> plan.item(
            meta = meta,
            type = DSecret.Type.SecureNote,
            fields = source.content.protonSectionFields() + source.extraFields.protonFields(),
        )
    }
}

private fun mapLogin(
    meta: ImportedItemMeta,
    source: ProtonPassContent,
    aliasEmail: String?,
    plan: ImportPlanBuilder,
) {
    val content = source.content
    val username = protonPassUsername(
        username = content.protonText("itemUsername") ?: content.protonText("username"),
        email = content.protonText("itemEmail"),
        aliasEmail = aliasEmail,
    )
    // Proton Pass stores a passkey in its own form, not as a key.
    (content["passkeys"] as? JsonArray)
        ?.size
        ?.let { plan.skip(ImportSkipReason.Passkey, meta.title, count = it) }
    val extraTotp = source.extraFields
        .firstOrNull { it.type == PROTON_FIELD_TYPE_TOTP }
        .takeIf { content.protonText("totpUri") == null }
    plan.login(
        meta = meta.copy(uris = content.protonUris()),
        username = username.username,
        password = content.protonRawText("password"),
        totp = content.protonText("totpUri") ?: extraTotp?.protonValue(),
        fields = username.fields + source.extraFields.filter { it !== extraTotp }.protonFields(),
    )
}

private fun mapCard(
    meta: ImportedItemMeta,
    source: ProtonPassContent,
    plan: ImportPlanBuilder,
) {
    val content = source.content
    val card = importedCard(
        cardholderName = content.protonText("cardholderName"),
        brand = content.protonInt("cardType")?.let(cardBrandByType::get),
        number = content.protonText("number"),
        code = content.protonText("verificationNumber") ?: content.protonText("verificationCode"),
        rawExpiry = content.protonText("expirationDate"),
        pin = content.protonText("pin"),
    )
    plan.item(meta, DSecret.Type.Card, card.fields + source.extraFields.protonFields()) {
        copy(card = card.card)
    }
}

private fun mapIdentity(
    meta: ImportedItemMeta,
    source: ProtonPassContent,
    plan: ImportPlanBuilder,
) {
    val content = source.content
    // The text members not read into the identity become custom fields.
    val members = ImportFieldBag(content.entries) { it.key }
    fun take(key: String): String? = content.protonText(key)
        ?.also { members.take(key) }
    val identity = CreateRequest.Identity(
        firstName = take("firstName") ?: take("fullName"),
        middleName = take("middleName"),
        lastName = take("lastName"),
        email = take("email"),
        phone = take("phoneNumber"),
        company = take("organization") ?: take("company"),
        address1 = take("streetAddress"),
        city = take("city"),
        state = take("stateOrProvince"),
        postalCode = take("zipOrPostalCode"),
        country = take("countryOrRegion"),
        ssn = take("socialSecurityNumber"),
        passportNumber = take("passportNumber"),
        licenseNumber = take("licenseNumber"),
    )
    val extras = identityExtraMembers.flatMap { key ->
        content.protonExtraFieldsOf(key).protonFields()
    }
    val sections = (content["extraSections"] as? JsonArray).orEmpty().protonSectionsToFields()
    val others = members.rest { (key, value) -> protonTextFieldOrNull(key, value) }
    plan.item(meta, DSecret.Type.Identity, others + extras + sections + source.extraFields.protonFields()) {
        copy(identity = identity)
    }
}

private fun mapSshKey(
    meta: ImportedItemMeta,
    source: ProtonPassContent,
    plan: ImportPlanBuilder,
    sshKeyImportService: SshKeyImportService,
) {
    val content = source.content
    // Proton allows a private key without its public key. Derive the pair
    // together so the agent can use it, even if the exported public key is wrong.
    plan.sshKeyOrNote(
        meta = meta,
        privateKeys = listOf(content.protonText("privateKey")),
        publicKey = content.protonText("publicKey"),
        fingerprint = content.protonText("fingerprint"),
        sshKeyImportService = sshKeyImportService,
        fields = content.protonSectionFields() + source.extraFields.protonFields(),
    )
}

private fun mapWifi(
    meta: ImportedItemMeta,
    source: ProtonPassContent,
    plan: ImportPlanBuilder,
) {
    val content = source.content
    plan.item(
        meta = meta,
        type = DSecret.Type.SecureNote,
        fields = listOfNotNull(
            importedFieldOrNull("SSID", content.protonText("ssid")),
            importedFieldOrNull("Password", content.protonRawText("password"), hidden = true),
            importedFieldOrNull("Security", content.protonInt("security")?.let(wifiSecurityByType::get)),
        ) + content.protonSectionFields() + source.extraFields.protonFields(),
    )
}
