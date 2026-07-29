package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.common.service.crypto.SshKeyImportService
import kotlin.reflect.KMutableProperty0

private const val YEAR_MONTH_PARTS = 2
private const val MONTH_MIN = 1
private const val MONTH_MAX = 12
private const val PEM_LINE_LENGTH = 64
private const val ADDRESS_LINE_1 = 0
private const val ADDRESS_LINE_2 = 1
private const val ADDRESS_EXTRA_LINES = 2

/**
 * The largest base64url private key an `ssh-key` credential may carry, taking
 * the same 64 KiB figure as the native layer's own key ceiling. Real keys are
 * an order of magnitude smaller — an RSA-16384 PKCS#8 encodes to roughly
 * 12 KiB — so this only ever rejects a payload built to exhaust memory.
 */
internal const val MAX_SSH_PRIVATE_KEY_B64_LENGTH = 64 * 1024

/**
 * The label an imported `validFrom` travels under when the source carries no
 * label of its own. Not translated, for the same reason as
 * [DEFAULT_FIELD_LABEL].
 *
 * CXF v1.0 §3.3.4 gives a credit card a `validFrom` year-month and Keyguard's
 * vault has nowhere to keep it: [CreateRequest.Card] declares
 * `fromMonth`/`fromYear`, but `AddCipher` builds `BitwardenCipher.Card` from
 * six of its eight members and never reads those two, so a value written there
 * is dropped at the vault write. A labelled custom field is the only place it
 * survives — the same bargain the `pin` member makes.
 */
internal const val CXF_CARD_VALID_FROM_LABEL = "Valid from"

/**
 * Maps an imported credit card into a card request together with the extra
 * fields (the PIN and the validity start) that have no dedicated Keyguard slot.
 * The inverse of [mapCreditCard].
 */
internal fun mapImportCreditCard(
    card: CxfCredential.CreditCard,
): ImportedCard {
    val expiry = mapImportYearMonth(card.expiryDate)
    val request = CreateRequest.Card(
        cardholderName = card.fullName?.blankAsNull(),
        brand = card.cardType?.blankAsNull(),
        number = card.number?.blankAsNull(),
        expMonth = expiry?.month,
        expYear = expiry?.year,
        code = card.verificationNumber?.blankAsNull(),
    )
    // The validity travels verbatim, like the pin: a conforming producer already
    // wrote the spec's `YYYY-MM`, and one that wrote something else is still
    // better read as text than dropped.
    val fields = listOfNotNull(
        card.pin?.toFieldOrNull(fallbackLabel = "PIN"),
        card.validFrom?.toFieldOrNull(fallbackLabel = CXF_CARD_VALID_FROM_LABEL),
    )
    return ImportedCard(
        card = request,
        fields = fields,
    )
}

internal data class ImportedCard(
    val card: CreateRequest.Card,
    val fields: List<DSecret.Field>,
) {
    /**
     * Whether importing this card would create a cipher with nothing in it.
     *
     * Compared against what the vault actually *persists*: `AddCipher` drops
     * `fromMonth`/`fromYear` on the floor, so a request differing only in those
     * two would materialise a wholly blank Card cipher — and, because
     * `CxfImportServiceImpl.parseItems` reads `requests.isEmpty()`, would also
     * hide the item from the `CxfImportSkipReason.Item` counter. Nothing on the
     * import path fills them any more (see [CXF_CARD_VALID_FROM_LABEL]); the
     * gate is written this way so that re-adding such a write cannot silently
     * bring the phantom cipher back.
     */
    val isEmpty: Boolean
        get() = card.asVaultPersisted() == CreateRequest.Card() && fields.isEmpty()
}

/**
 * The card as the vault will store it: the two members `AddCipher` never reads
 * are cleared, so comparing the result against an all-defaults card asks
 * "would the stored cipher be blank?".
 */
private fun CreateRequest.Card.asVaultPersisted(): CreateRequest.Card = copy(
    fromMonth = null,
    fromYear = null,
)

private data class ImportedYearMonth(
    val year: String,
    val month: String,
)

/**
 * Splits a `YYYY-MM` year-month value into its parts, or returns `null` when
 * malformed. The inverse of [yearMonthOrNull].
 */
private fun mapImportYearMonth(
    field: CxfEditableField?,
): ImportedYearMonth? {
    val parts = field?.value
        ?.trim()
        ?.split('-')
        ?.takeIf { it.size == YEAR_MONTH_PARTS }
        ?: return null
    val year = parts[0].toIntOrNull()?.takeIf { it >= 0 }
    val month = parts[1].toIntOrNull()?.takeIf { it in MONTH_MIN..MONTH_MAX }
    return if (year != null && month != null) {
        ImportedYearMonth(
            year = year.toString(),
            month = month.toString(),
        )
    } else {
        null
    }
}

internal data class CxfIdentityCredentials(
    val personName: CxfCredential.PersonName? = null,
    val address: CxfCredential.Address? = null,
    val passport: CxfCredential.Passport? = null,
    val driversLicense: CxfCredential.DriversLicense? = null,
    val identityDocument: CxfCredential.IdentityDocument? = null,
) {
    val isEmpty: Boolean
        get() = this == CxfIdentityCredentials()
}

internal data class ImportedIdentity(
    val identity: CreateRequest.Identity,
    /**
     * Values that had no free Keyguard identity slot, preserved as custom
     * fields so nothing from the source document is dropped.
     */
    val fields: List<DSecret.Field>,
    /**
     * The item's custom fields that were not consumed into identity slots.
     */
    val remainingCustomFields: List<CxfEditableField>,
)

/**
 * Re-merges the identity-shaped credentials back into a single identity.
 *
 * Slots fill on a first-come basis in a fixed priority order; a value whose
 * slot is already taken is preserved as a custom field instead of being
 * dropped. Custom fields whose labels exactly match the exporter's overflow
 * labels ([CxfIdentityOverflowLabels]) fill their empty slots and are
 * consumed; everything else is passed through in
 * [ImportedIdentity.remainingCustomFields].
 */
internal fun mapImportIdentity(
    credentials: CxfIdentityCredentials,
    customFields: List<CxfEditableField>,
): ImportedIdentity {
    val slots = IdentitySlots()
    slots.applyPersonName(credentials.personName)
    slots.applyAddress(credentials.address)
    slots.applyPassport(credentials.passport)
    slots.applyDriversLicense(credentials.driversLicense)
    slots.applyIdentityDocument(credentials.identityDocument)
    val remaining = slots.consumeCustomFields(customFields)
    return ImportedIdentity(
        identity = slots.toIdentity(),
        fields = slots.extraFields,
        remainingCustomFields = remaining,
    )
}

/**
 * The mutable slot-filling state behind [mapImportIdentity]. Each `fill` call
 * either claims an empty slot or overflows the value into [extraFields].
 */
private class IdentitySlots {
    val extraFields = mutableListOf<DSecret.Field>()

    var title: String? = null
    var firstName: String? = null
    var middleName: String? = null
    var lastName: String? = null
    var address1: String? = null
    var address2: String? = null
    var address3: String? = null
    var city: String? = null
    var state: String? = null
    var postalCode: String? = null
    var country: String? = null
    var company: String? = null
    var email: String? = null
    var phone: String? = null
    var ssn: String? = null
    var username: String? = null
    var passportNumber: String? = null
    var licenseNumber: String? = null

    fun applyPersonName(personName: CxfCredential.PersonName?) {
        personName ?: return
        title = fill(title, "Title", personName.title)
        firstName = fill(firstName, "First name", personName.given)
        middleName = fill(middleName, "Middle name", personName.given2)
        val surname = joinNonBlank(
            personName.surnamePrefix?.value,
            personName.surname?.value,
            personName.surname2?.value,
        )
        lastName = fill(lastName, "Last name", value = surname)
        // Professional credentials/suffixes have no dedicated slot; the company
        // slot is the closest free-text identity field to hold them.
        company = fill(company, "Credentials", personName.credentials)
        overflow(personName.givenInformal, "Informal name")
        overflow(personName.generation, "Generation")
    }

    fun applyAddress(address: CxfCredential.Address?) {
        address ?: return
        val lines = address.streetAddress
            ?.value
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        address1 = fill(address1, "Street address", value = lines.getOrNull(ADDRESS_LINE_1))
        address2 = fill(address2, "Street address", value = lines.getOrNull(ADDRESS_LINE_2))
        val rest = lines.drop(ADDRESS_EXTRA_LINES)
            .takeIf { it.isNotEmpty() }
            ?.joinToString(separator = ", ")
        address3 = fill(address3, "Street address", value = rest)
        city = fill(city, "City", address.city)
        state = fill(state, "Territory", address.territory)
        postalCode = fill(postalCode, "Postal code", address.postalCode)
        country = fill(country, "Country", address.country)
        phone = fill(phone, "Phone", address.tel)
    }

    fun applyPassport(passport: CxfCredential.Passport?) {
        passport ?: return
        fillFullName(passport.fullName, "Passport full name")
        passportNumber = fill(passportNumber, "Passport number", passport.passportNumber)
        ssn = fill(ssn, "National identification number", passport.nationalIdentificationNumber)
        country = fill(country, "Passport issuing country", passport.issuingCountry)
        overflow(passport.nationality, "Nationality")
        overflow(passport.birthDate, "Birth date")
        overflow(passport.birthPlace, "Birth place")
        overflow(passport.sex, "Sex")
        overflow(passport.issueDate, "Passport issue date")
        overflow(passport.expiryDate, "Passport expiry date")
        overflow(passport.issuingAuthority, "Passport issuing authority")
        overflow(passport.passportType, "Passport type")
    }

    fun applyDriversLicense(license: CxfCredential.DriversLicense?) {
        license ?: return
        fillFullName(license.fullName, "License full name")
        licenseNumber = fill(licenseNumber, "License number", license.licenseNumber)
        state = fill(state, "License territory", license.territory)
        country = fill(country, "License country", license.country)
        overflow(license.birthDate, "Birth date")
        overflow(license.issueDate, "License issue date")
        overflow(license.expiryDate, "License expiry date")
        overflow(license.issuingAuthority, "License issuing authority")
        overflow(license.licenseClass, "License class")
    }

    fun applyIdentityDocument(document: CxfCredential.IdentityDocument?) {
        document ?: return
        fillFullName(document.fullName, "Document full name")
        ssn = fill(ssn, "Identification number", document.identificationNumber)
        passportNumber = fill(passportNumber, "Document number", document.documentNumber)
        country = fill(country, "Document issuing country", document.issuingCountry)
        overflow(document.nationality, "Nationality")
        overflow(document.birthDate, "Birth date")
        overflow(document.birthPlace, "Birth place")
        overflow(document.sex, "Sex")
        overflow(document.issueDate, "Document issue date")
        overflow(document.expiryDate, "Document expiry date")
        overflow(document.issuingAuthority, "Document issuing authority")
    }

    /**
     * Fills empty slots from custom fields carrying the exporter's own
     * overflow labels and returns the fields that were not consumed.
     */
    fun consumeCustomFields(
        fields: List<CxfEditableField>,
    ): List<CxfEditableField> = fields.filter { field ->
        val value = field.value.takeIf { it.isNotBlank() }
        val slot = value?.let { slotFor(field.label) }
        if (slot != null && slot.get() == null) {
            slot.set(value)
            false
        } else {
            true
        }
    }

    /**
     * The identity slot a custom-field label maps to; the labels are shared
     * with the exporter through [CxfIdentityOverflowLabels].
     */
    private fun slotFor(
        label: String?,
    ): KMutableProperty0<String?>? = when (label) {
        CxfIdentityOverflowLabels.COMPANY -> ::company
        CxfIdentityOverflowLabels.EMAIL -> ::email
        CxfIdentityOverflowLabels.USERNAME -> ::username
        CxfIdentityOverflowLabels.SSN -> ::ssn
        CxfIdentityOverflowLabels.PASSPORT_NUMBER -> ::passportNumber
        CxfIdentityOverflowLabels.LICENSE_NUMBER -> ::licenseNumber
        else -> null
    }

    fun toIdentity(): CreateRequest.Identity = CreateRequest.Identity(
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

    private fun fillFullName(
        fullName: CxfEditableField?,
        overflowLabel: String,
    ) {
        val value = fullName?.blankAsNull()
            ?: return
        if (firstName == null && lastName == null) {
            val parts = value.split(' ', limit = 2)
            firstName = parts.getOrNull(0)
            lastName = parts.getOrNull(1)
        } else {
            overflow(fullName, overflowLabel)
        }
    }

    /**
     * Claims the slot for the value when it is still free; a value whose
     * slot is already taken overflows into [extraFields] under [label].
     */
    private fun fill(
        current: String?,
        label: String,
        field: CxfEditableField? = null,
        value: String? = field?.blankAsNull(),
    ): String? {
        val filler = value?.takeIf { it.isNotBlank() }
            ?: return current
        return if (current != null) {
            extraFields += importedField(
                name = label,
                value = filler,
                type = field?.fieldTypeToFieldType() ?: DSecret.Field.Type.Text,
            )
            current
        } else {
            filler
        }
    }

    private fun overflow(
        field: CxfEditableField?,
        label: String,
    ) {
        field?.toFieldOrNull(fallbackLabel = label)
            ?.let(extraFields::add)
    }
}

/**
 * Maps an imported SSH key credential — a base64url PKCS#8 DER private key —
 * into Keyguard's stored OpenSSH form via the native crypto layer, or returns
 * `null` (a counted skip) when the key cannot be decoded or converted.
 * The metadata members (comment, dates, generation source) have no dedicated
 * Keyguard slots and are preserved as custom fields.
 */
internal fun mapImportSshKey(
    credential: CxfCredential.SshKey,
    sshKeyImportService: SshKeyImportService,
): ImportedSshKey? {
    // An unsupported `keyType`, a private key the native seam refuses, and a
    // recovered key whose type contradicts the declared one are the same
    // outcome — the credential is not importable — so they share one bail-out.
    val keyPair = credential.keyType.toSupportedCxfSshKeyType()
        ?.let { declaredType ->
            importSshKeyPair(credential.privateKey, sshKeyImportService)
                ?.takeIf { it.type == declaredType }
        }
        ?: return null
    val fields = buildList {
        credential.keyComment
            ?.takeIf { it.isNotBlank() }
            ?.let { comment ->
                add(
                    importedField(
                        name = "Key comment",
                        value = comment,
                        type = DSecret.Field.Type.Text,
                    ),
                )
            }
        credential.creationDate?.toFieldOrNull(fallbackLabel = "Creation date")?.let(::add)
        credential.expiryDate?.toFieldOrNull(fallbackLabel = "Expiry date")?.let(::add)
        credential.keyGenerationSource
            ?.toFieldOrNull(fallbackLabel = "Key generation source")
            ?.let(::add)
    }
    return ImportedSshKey(
        sshKey = CreateRequest.SshKey(
            privateKey = keyPair.privateKey.ssh,
            publicKey = keyPair.publicKey.ssh,
            fingerprint = keyPair.publicKey.fingerprint,
        ),
        fields = fields,
    )
}

private fun String.toSupportedCxfSshKeyType(): KeyPair.Type? = when (this) {
    "ssh-ed25519" -> KeyPair.Type.ED25519
    "ssh-rsa",
    "rsa-sha2-256",
    "rsa-sha2-512",
    -> KeyPair.Type.RSA

    else -> null
}

internal data class ImportedSshKey(
    val sshKey: CreateRequest.SshKey,
    val fields: List<DSecret.Field>,
)

/**
 * Decodes the base64url PKCS#8 DER, wraps it into a PEM and runs it through
 * the conversion seam. Returns `null` — a counted skip — when the value is
 * oversized, is not base64url, or cannot be converted.
 */
private fun importSshKeyPair(
    privateKey: String,
    sshKeyImportService: SshKeyImportService,
): KeyPair? {
    // The decode/re-encode/chunk round trip below allocates a multiple of the
    // credential's size, and the native seam rejects anything past its own
    // limit anyway — so bound the input before touching it rather than after.
    val der = privateKey
        .takeIf { it.length <= MAX_SSH_PRIVATE_KEY_B64_LENGTH }
        ?.let { encoded -> runCatchingNonFatal { cxfUrlSafeBase64.decode(encoded) }.getOrNull() }
        ?: return null
    val pem = try {
        buildPkcs8Pem(der)
    } finally {
        der.fill(0)
    }
    // The seam is not total: an oversized or otherwise rejected key raises
    // rather than returning an error result, and this runs inside `parse`,
    // whose contract is that only a malformed document fails it. Fatal
    // failures are *not* absorbed here — they belong to the parse boundary
    // above, which decides whether the whole document dies.
    val result = runCatchingNonFatal {
        sshKeyImportService.import(
            SshKeyImportRequest(
                content = pem,
            ),
        )
    }.getOrNull()
    return (result as? SshKeyImportResult.Success)?.keyPair
}

private fun buildPkcs8Pem(
    der: ByteArray,
): String = buildString {
    append("-----BEGIN PRIVATE KEY-----\n")
    cxfStandardBase64.encode(der)
        .chunked(PEM_LINE_LENGTH)
        .forEach { line ->
            append(line)
            append('\n')
        }
    append("-----END PRIVATE KEY-----\n")
}

private fun joinNonBlank(
    vararg values: String?,
): String? = values
    .mapNotNull { it?.takeIf { value -> value.isNotBlank() } }
    .takeIf { it.isNotEmpty() }
    ?.joinToString(separator = " ")
