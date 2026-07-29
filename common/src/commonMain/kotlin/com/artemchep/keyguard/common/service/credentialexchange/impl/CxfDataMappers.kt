package com.artemchep.keyguard.common.service.credentialexchange.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredential
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfCredentialType
import com.artemchep.keyguard.common.service.credentialexchange.model.CxfEditableField

/**
 * The separator the three street-address lines are packed into one `string`
 * value with.
 *
 * A literal `\n`, never the host's `LeSystem.lineSeparator`: this is part of the
 * wire format, so it must be the same byte on every platform Keyguard exports
 * from. The host separator emits `\r\n` on Windows, which leaves a reader that
 * splits on `'\n'` — as Keyguard's own importer does — a trailing carriage
 * return on every line but the last.
 *
 * A line that itself contains a break is *not* handled here: it is emitted
 * verbatim and any reader re-splits it positionally, which shifts the lines after
 * it down a slot. The round-trip suite pins that shift (`CxfRoundTripEncodingTest`,
 * "an address line containing a newline shifts the later lines"), so changing it
 * is a decision about that behaviour, not about this separator.
 */
private const val ADDRESS_LINE_SEPARATOR = "\n"

private const val YEAR_PAD = 4
private const val MONTH_PAD = 2
private const val MONTH_MIN = 1
private const val MONTH_MAX = 12
private const val TWO_DIGIT_YEAR_MAX = 99
private const val TWO_DIGIT_YEAR_BASE = 2000

/**
 * Maps a Keyguard card into a credit-card credential, or `null` when there is
 * nothing to export.
 */
internal fun mapCreditCard(
    card: DSecret.Card,
): CxfCredential.CreditCard? {
    val credential = CxfCredential.CreditCard(
        number = editableFieldOrNull(card.number, CxfEditableField.FIELD_TYPE_CONCEALED_STRING),
        fullName = editableFieldOrNull(card.cardholderName, CxfEditableField.FIELD_TYPE_STRING),
        cardType = editableFieldOrNull(card.brand, CxfEditableField.FIELD_TYPE_STRING),
        verificationNumber = editableFieldOrNull(card.code, CxfEditableField.FIELD_TYPE_CONCEALED_STRING),
        expiryDate = yearMonthOrNull(card.expMonth, card.expYear),
        validFrom = yearMonthOrNull(card.fromMonth, card.fromYear),
    )
    return credential.takeUnless { it == CxfCredential.CreditCard() }
}

/**
 * Builds a year-month [CxfEditableField] (value `YYYY-MM`) from the given
 * month/year strings, or `null` when either is missing or not a valid number.
 *
 * A two-digit year is shifted into the 2000-2099 range ("25" becomes 2025).
 */
internal fun yearMonthOrNull(
    month: String?,
    year: String?,
): CxfEditableField? {
    val monthValue = month?.trim()?.toIntOrNull()
        ?.takeIf { it in MONTH_MIN..MONTH_MAX }
    val yearValue = year?.trim()?.toIntOrNull()
        ?.takeIf { it >= 0 }
        ?.let { value ->
            if (value <= TWO_DIGIT_YEAR_MAX) value + TWO_DIGIT_YEAR_BASE else value
        }
    if (monthValue == null || yearValue == null) {
        return null
    }
    val value = buildString {
        append(yearValue.toString().padStart(YEAR_PAD, '0'))
        append('-')
        append(monthValue.toString().padStart(MONTH_PAD, '0'))
    }
    return CxfEditableField(
        fieldType = CxfEditableField.FIELD_TYPE_YEAR_MONTH,
        value = value,
    )
}

/**
 * Maps a Keyguard identity into person-name + address + custom-fields
 * credentials, each gated by [allowedTypes] and only emitted when non-empty.
 */
internal fun mapIdentityCredentials(
    identity: DSecret.Identity,
    allowedTypes: Set<CxfCredentialType>,
): List<CxfCredential> = buildList {
    if (isAllowed(allowedTypes, CxfCredentialType.PersonName)) {
        buildPersonName(identity)?.let(::add)
    }
    if (isAllowed(allowedTypes, CxfCredentialType.Address)) {
        buildAddress(identity)?.let(::add)
    }
    if (isAllowed(allowedTypes, CxfCredentialType.CustomFields)) {
        buildIdentityCustomFields(identity)?.let(::add)
    }
}

private fun buildPersonName(
    identity: DSecret.Identity,
): CxfCredential.PersonName? {
    val credential = CxfCredential.PersonName(
        title = editableFieldOrNull(identity.title, CxfEditableField.FIELD_TYPE_STRING),
        given = editableFieldOrNull(identity.firstName, CxfEditableField.FIELD_TYPE_STRING),
        given2 = editableFieldOrNull(identity.middleName, CxfEditableField.FIELD_TYPE_STRING),
        surname = editableFieldOrNull(identity.lastName, CxfEditableField.FIELD_TYPE_STRING),
    )
    return credential.takeUnless { it == CxfCredential.PersonName() }
}

private fun buildAddress(
    identity: DSecret.Identity,
): CxfCredential.Address? {
    val streetAddress = listOfNotNull(
        identity.address1?.takeIf { it.isNotBlank() },
        identity.address2?.takeIf { it.isNotBlank() },
        identity.address3?.takeIf { it.isNotBlank() },
    )
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = ADDRESS_LINE_SEPARATOR)
    val credential = CxfCredential.Address(
        streetAddress = editableFieldOrNull(streetAddress, CxfEditableField.FIELD_TYPE_STRING),
        postalCode = editableFieldOrNull(identity.postalCode, CxfEditableField.FIELD_TYPE_STRING),
        city = editableFieldOrNull(identity.city, CxfEditableField.FIELD_TYPE_STRING),
        territory = editableFieldOrNull(identity.state, CxfEditableField.FIELD_TYPE_SUBDIVISION_CODE),
        country = editableFieldOrNull(identity.country, CxfEditableField.FIELD_TYPE_COUNTRY_CODE),
        tel = editableFieldOrNull(identity.phone, CxfEditableField.FIELD_TYPE_STRING),
    )
    return credential.takeUnless { it == CxfCredential.Address() }
}

private fun buildIdentityCustomFields(
    identity: DSecret.Identity,
): CxfCredential.CustomFields? {
    val fields = listOfNotNull(
        editableFieldOrNull(
            identity.company,
            CxfEditableField.FIELD_TYPE_STRING,
            CxfIdentityOverflowLabels.COMPANY,
        ),
        editableFieldOrNull(
            identity.email,
            CxfEditableField.FIELD_TYPE_EMAIL,
            CxfIdentityOverflowLabels.EMAIL,
        ),
        editableFieldOrNull(
            identity.username,
            CxfEditableField.FIELD_TYPE_STRING,
            CxfIdentityOverflowLabels.USERNAME,
        ),
        editableFieldOrNull(
            identity.ssn,
            CxfEditableField.FIELD_TYPE_CONCEALED_STRING,
            CxfIdentityOverflowLabels.SSN,
        ),
        editableFieldOrNull(
            identity.passportNumber,
            CxfEditableField.FIELD_TYPE_CONCEALED_STRING,
            CxfIdentityOverflowLabels.PASSPORT_NUMBER,
        ),
        editableFieldOrNull(
            identity.licenseNumber,
            CxfEditableField.FIELD_TYPE_CONCEALED_STRING,
            CxfIdentityOverflowLabels.LICENSE_NUMBER,
        ),
    )
    if (fields.isEmpty()) {
        return null
    }
    return CxfCredential.CustomFields(
        fields = fields,
    )
}

/**
 * Maps free-form notes into a note credential,
 * or `null` when blank.
 */
internal fun mapNote(
    notes: String,
): CxfCredential.Note? {
    val content = editableFieldOrNull(notes, CxfEditableField.FIELD_TYPE_STRING)
        ?: return null
    return CxfCredential.Note(
        content = content,
    )
}

/**
 * Maps Keyguard custom fields into a custom-fields credential. Text, hidden and
 * boolean fields are carried over; linked fields are dropped.
 * Returns `null` when nothing remains.
 */
internal fun mapCustomFields(
    fields: List<DSecret.Field>,
): CxfCredential.CustomFields? {
    val editableFields = fields.mapNotNull { field ->
        val fieldType = when (field.type) {
            DSecret.Field.Type.Text -> CxfEditableField.FIELD_TYPE_STRING
            DSecret.Field.Type.Hidden -> CxfEditableField.FIELD_TYPE_CONCEALED_STRING
            DSecret.Field.Type.Boolean -> CxfEditableField.FIELD_TYPE_BOOLEAN
            DSecret.Field.Type.Linked -> return@mapNotNull null
        }
        val value = field.value
            ?: return@mapNotNull null
        CxfEditableField(
            fieldType = fieldType,
            value = value,
            label = field.name?.takeIf { it.isNotBlank() },
        )
    }
    if (editableFields.isEmpty()) {
        return null
    }
    return CxfCredential.CustomFields(
        fields = editableFields,
    )
}

private fun editableFieldOrNull(
    value: String?,
    fieldType: String,
    label: String? = null,
): CxfEditableField? = value
    ?.takeIf { it.isNotBlank() }
    ?.let {
        CxfEditableField(
            fieldType = fieldType,
            value = it,
            label = label,
        )
    }
