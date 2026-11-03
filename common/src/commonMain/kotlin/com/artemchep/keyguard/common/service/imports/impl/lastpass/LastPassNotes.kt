package com.artemchep.keyguard.common.service.imports.impl.lastpass

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import com.artemchep.keyguard.common.service.imports.impl.ImportPlanBuilder
import com.artemchep.keyguard.common.service.imports.impl.importJson
import com.artemchep.keyguard.common.service.imports.impl.item
import com.artemchep.keyguard.common.service.imports.impl.sshKeyOrNote
import com.artemchep.keyguard.common.service.imports.mapping.IMPORT_DEFAULT_FIELD_LABEL
import com.artemchep.keyguard.common.service.imports.mapping.ImportFieldBag
import com.artemchep.keyguard.common.service.imports.mapping.ImportedCard
import com.artemchep.keyguard.common.service.imports.mapping.ImportedItemMeta
import com.artemchep.keyguard.common.service.imports.mapping.hasPublicKey
import com.artemchep.keyguard.common.service.imports.mapping.importedCard
import com.artemchep.keyguard.common.service.imports.mapping.importedFieldOrNull
import com.artemchep.keyguard.common.service.imports.mapping.orNullIfBlank
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val NOTE_TYPE_KEY = "NoteType"
private const val NOTES_KEY = "Notes"
private const val LANGUAGE_KEY = "Language"

private const val NOTE_TYPE_CARD = "Credit Card"
private const val NOTE_TYPE_ADDRESS = "Address"
private const val NOTE_TYPE_SSH_KEY = "SSH Key"

private const val SSH_PRIVATE_KEY = "Private Key"
private const val SSH_PUBLIC_KEY = "Public Key"
private const val SSH_PASSPHRASE = "Passphrase"

private const val CARD_EXPIRY = "Expiration Date"
private const val CARD_VALID_FROM = "Start Date"

/**
 * The keys of an SSH key note. Inside its private key, any other `Key:`
 * line — such as `Proc-Type:` — belongs to the key.
 */
private val sshKeyNoteKeys = setOf(
    LANGUAGE_KEY, "Bit Strength", "Format", SSH_PASSPHRASE, SSH_PRIVATE_KEY,
    SSH_PUBLIC_KEY, "Hostname", "Date", NOTES_KEY,
)

/**
 * Values that are secrets, shown hidden.
 */
private val sensitiveKeys = setOf(
    "Password", "Passphrase", SSH_PRIVATE_KEY, "Pin", "PIN", "Security Code",
    "Account Number", "IBAN Number",
)

/**
 * A structured note: its type, its values in order, and its trailing notes.
 * The values read into a member are taken out; the rest become custom fields.
 */
private class LastPassStructuredNote(
    val type: String,
    val values: ImportFieldBag<Pair<String, String>>,
    val notes: String?,
)

/**
 * Plans a secure note, whose text is the [meta]'s note. A structured one
 * becomes a card, an identity, or a note whose values are custom fields.
 */
internal fun mapLastPassNote(
    meta: ImportedItemMeta,
    plan: ImportPlanBuilder,
    sshKeyImportService: SshKeyImportService,
) {
    val structured = meta.note
        ?.takeIf { it.startsWith("$NOTE_TYPE_KEY:") }
        ?.let(::parseStructuredNote)
    if (structured == null) {
        plan.item(meta, DSecret.Type.SecureNote)
        return
    }
    val structuredMeta = meta.copy(note = structured.notes)
    when (structured.type) {
        NOTE_TYPE_CARD -> {
            val card = structured.takeCard()
            plan.item(structuredMeta, DSecret.Type.Card, card.fields + structured.fields()) {
                copy(card = card.card)
            }
        }

        NOTE_TYPE_ADDRESS -> {
            val identity = structured.takeIdentity()
            plan.item(structuredMeta, DSecret.Type.Identity, structured.fields()) {
                copy(identity = identity)
            }
        }

        NOTE_TYPE_SSH_KEY -> structured.planSshKey(structuredMeta, plan, sshKeyImportService)

        else -> plan.item(structuredMeta, DSecret.Type.SecureNote, structured.fields())
    }
}

/** Keeps the source fields until conversion succeeds, including encrypted keys on failure. */
private fun LastPassStructuredNote.planSshKey(
    meta: ImportedItemMeta,
    plan: ImportPlanBuilder,
    sshKeyImportService: SshKeyImportService,
) = plan.sshKeyOrNote(
    meta = meta,
    privateKeys = listOf(values[SSH_PRIVATE_KEY]?.second),
    sshKeyImportService = sshKeyImportService,
    passphrase = values[SSH_PASSPHRASE]?.second,
) { key ->
    if (key != null) {
        values.take(SSH_PRIVATE_KEY)
        if (values[SSH_PUBLIC_KEY]?.second?.let(key::hasPublicKey) == true) {
            values.take(SSH_PUBLIC_KEY)
        }
    }
    // SSH fields are not dates: commas and whitespace can be a passphrase.
    values.rest { (name, value) ->
        value.takeIf { it.isNotEmpty() }?.let {
            DSecret.Field(
                name = name.orNullIfBlank() ?: IMPORT_DEFAULT_FIELD_LABEL,
                value = it,
                type = if (name in sensitiveKeys) DSecret.Field.Type.Hidden else DSecret.Field.Type.Text,
            )
        }
    }
}

/**
 * `Key:Value` lines. A line without a colon continues the value before it,
 * as a multi-line value does; the `Notes` value runs to the end.
 */
private fun parseStructuredNote(
    extra: String,
): LastPassStructuredNote {
    val lines = extra.lines()
    val type = lines.first().substringAfter(':').trim()
    val values = linkedMapOf<String, String>()
    var currentKey: String? = null
    var notes: String? = null
    for ((index, line) in lines.withIndex().drop(1)) {
        val key = line.substringBefore(':', missingDelimiterValue = "")
        val value = line.substringAfter(':', missingDelimiterValue = "")
        val continues = currentKey != null && (
            key.isEmpty() ||
                type == NOTE_TYPE_SSH_KEY && currentKey == SSH_PRIVATE_KEY && key !in sshKeyNoteKeys
            )
        when {
            key == NOTES_KEY && !continues -> {
                notes = (listOf(value) + lines.drop(index + 1)).joinToString("\n")
                break
            }

            continues -> values[currentKey] = values[currentKey].orEmpty() + "\n" + line

            else -> {
                values[key] = value
                currentKey = key
            }
        }
    }
    values.remove(LANGUAGE_KEY)
    return LastPassStructuredNote(
        type = type,
        values = ImportFieldBag(values.toList()) { it.first },
        notes = notes.orNullIfBlank(),
    )
}

/** Takes a mapped value, preserving nonempty secrets verbatim. */
private fun LastPassStructuredNote.take(key: String): String? = values.take(key)?.second
    ?.takeIf { if (key in sensitiveKeys) it.isNotEmpty() else it.isNotBlank() }

/** LastPass writes empty dates as `,` or `,,`; other fields may contain literal commas. */
private fun String.isBlankDate() = all { it == ',' || it.isWhitespace() }

private fun LastPassStructuredNote.fields(): List<DSecret.Field> = values.rest { (key, value) ->
    importedFieldOrNull(
        name = key,
        value = value,
        hidden = key in sensitiveKeys,
    )
}

/**
 * The card. A date that cannot be read stays a field under its own label;
 * one LastPass left empty is dropped.
 */
private fun LastPassStructuredNote.takeCard(): ImportedCard {
    val rawExpiry = values[CARD_EXPIRY]?.second
    val expiry = rawExpiry?.let(::parseLastPassExpiry)
    if (expiry != null || rawExpiry?.isBlankDate() == true) {
        values.take(CARD_EXPIRY)
    }
    val rawValidFrom = values[CARD_VALID_FROM]?.second
    val validFrom = rawValidFrom?.let(::parseLastPassExpiry)
        ?.takeIf { it.month != null && it.year != null }
    if (validFrom != null || rawValidFrom?.isBlankDate() == true) {
        values.take(CARD_VALID_FROM)
    }
    return importedCard(
        cardholderName = take("Name on Card"),
        brand = take("Type"),
        number = take("Number"),
        code = take("Security Code"),
        expiry = expiry,
        validFrom = validFrom,
    )
}

private fun LastPassStructuredNote.takeIdentity(): CreateRequest.Identity = CreateRequest.Identity(
    title = take("Title"),
    firstName = take("First Name"),
    middleName = take("Middle Name"),
    lastName = take("Last Name"),
    username = take("Username"),
    company = take("Company"),
    address1 = take("Address 1"),
    address2 = take("Address 2"),
    address3 = take("Address 3"),
    city = take("City / Town"),
    state = take("State"),
    postalCode = take("Zip / Postal Code"),
    country = take("Country"),
    email = take("Email Address"),
    phone = take("Phone")?.let(::parsePhone),
).also {
    // The other phone numbers stay fields, readable rather than as JSON.
    listOf("Evening Phone", "Mobile Phone", "Fax").forEach { key ->
        values.update(key) { (name, value) -> name to parsePhone(value) }
    }
}

/**
 * LastPass stores a phone number as `{"num":"…","ext":"…"}`.
 */
private fun parsePhone(
    raw: String,
): String {
    val obj = runCatchingNonFatal {
        importJson.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()
        ?: return raw
    val number = (obj["num"] as? JsonPrimitive)?.contentOrNull.orNullIfBlank()
    val extension = (obj["ext"] as? JsonPrimitive)?.contentOrNull.orNullIfBlank()
    return listOfNotNull(number, extension?.let { "ext. $it" })
        .joinToString(" ")
        .ifBlank { raw }
}

