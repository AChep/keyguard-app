package com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.codec

import app.keemobile.kotpass.models.Entry
import kotlin.time.Instant

object KeePassFieldKey {
    const val TAG_FAVORITE = "Favorite"
    const val AUTH_REPROMPT = "Authentication Re-Prompt"
    const val PASSWORD_REVISION_DATE = "Password Revision Date"
    const val ARCHIVED_DATE = "Archived Date"
    const val SOFT_DELETED_DATE = "Soft-Deleted Date"

    // Card
    const val CARD_CARDHOLDER_NAME = "card_cardholderName"
    const val CARD_BRAND = "card_brand"
    const val CARD_NUMBER = "card_number"
    const val CARD_EXP_MONTH = "card_expMonth"
    const val CARD_EXP_YEAR = "card_expYear"
    const val CARD_CODE = "card_code"

    // Identity
    const val IDENTITY_TITLE = "identity_title"
    const val IDENTITY_NAME = "identity_name"
    const val IDENTITY_FIRST_NAME = "identity_firstName"
    const val IDENTITY_MIDDLE_NAME = "identity_middleName"
    const val IDENTITY_LAST_NAME = "identity_lastName"
    const val IDENTITY_ADDRESS = "identity_address"
    const val IDENTITY_ADDRESS1 = "identity_address1"
    const val IDENTITY_ADDRESS2 = "identity_address2"
    const val IDENTITY_ADDRESS3 = "identity_address3"
    const val IDENTITY_CITY = "identity_city"
    const val IDENTITY_STATE = "identity_state"
    const val IDENTITY_POSTAL_CODE = "identity_postalCode"
    const val IDENTITY_COUNTRY = "identity_country"
    const val IDENTITY_COMPANY = "identity_company"
    const val IDENTITY_EMAIL = "identity_email"
    const val IDENTITY_PHONE = "identity_phone"
    const val IDENTITY_SSN = "identity_ssn"
    const val IDENTITY_PASSPORT_NUMBER = "identity_passportNumber"
    const val IDENTITY_LICENSE_NUMBER = "identity_licenseNumber"

    // SSH Key
    const val SSH_PRIVATE_KEY = "ssh_privateKey"
    const val SSH_PUBLIC_KEY = "ssh_publicKey"
    const val SSH_FINGERPRINT = "ssh_fingerprint"
}

/**
 * Reads the Keyguard soft-trash timestamp persisted on a KeePass entry as the
 * reserved [KeePassFieldKey.SOFT_DELETED_DATE] field. Returns null when the
 * field is absent or unparseable. Shared by [KeePassCipherCodec] (decode) and
 * the coordinator's cipher extraction so both interpret the field identically.
 */
internal fun readSoftDeletedDate(entry: Entry): Instant? =
    entry.fields[KeePassFieldKey.SOFT_DELETED_DATE]?.content?.let(Instant::parseOrNull)
