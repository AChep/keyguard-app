package com.artemchep.keyguard.provider.bitwarden.mapper

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher

suspend fun BitwardenCipher.toDomain(
    getPasswordStrength: GetPasswordStrength,
): DSecret {
    val type: DSecret.Type = when (type) {
        BitwardenCipher.Type.Login -> DSecret.Type.Login
        BitwardenCipher.Type.SecureNote -> DSecret.Type.SecureNote
        BitwardenCipher.Type.Card -> DSecret.Type.Card
        BitwardenCipher.Type.Identity -> DSecret.Type.Identity
    }
    return DSecret(
        id = cipherId,
        accountId = accountId,
        folderId = folderId,
        organizationId = organizationId,
        collectionIds = collectionIds,
        revisionDate = revisionDate,
        createdDate = createdDate,
        deletedDate = deletedDate,
        service = service,
        // common
        name = name.orEmpty(),
        notes = notes.orEmpty(),
        favorite = favorite,
        reprompt = reprompt == BitwardenCipher.RepromptType.Password,
        synced = !service.deleted &&
                revisionDate == service.remote?.revisionDate,
        uris = login?.uris.orEmpty().map(BitwardenCipher.Login.Uri::toDomain),
        fields = fields.map(BitwardenCipher.Field::toDomain),
        attachments = attachments
            .mapNotNull { attachment ->
                when (attachment) {
                    is BitwardenCipher.Attachment.Remote -> {
                        val remoteCipherId = service.remote?.id
                            ?: return@mapNotNull null // must not happen
                        attachment.toDomain(remoteCipherId = remoteCipherId)
                    }

                    is BitwardenCipher.Attachment.Local -> attachment.toDomain()
                }
            },
        // types
        type = type,
        login = login?.toDomain(getPasswordStrength),
        card = card?.toDomain(),
        identity = identity?.toDomain(),
    )
}

fun BitwardenCipher.Login.Uri.toDomain() = DSecret.Uri(
    uri = uri.orEmpty(),
    match = match?.toDomain(),
)

fun BitwardenCipher.Login.Uri.MatchType.toDomain() = when (this) {
    BitwardenCipher.Login.Uri.MatchType.Domain -> DSecret.Uri.MatchType.Domain
    BitwardenCipher.Login.Uri.MatchType.Host -> DSecret.Uri.MatchType.Host
    BitwardenCipher.Login.Uri.MatchType.StartsWith -> DSecret.Uri.MatchType.StartsWith
    BitwardenCipher.Login.Uri.MatchType.Exact -> DSecret.Uri.MatchType.Exact
    BitwardenCipher.Login.Uri.MatchType.RegularExpression -> DSecret.Uri.MatchType.RegularExpression
    BitwardenCipher.Login.Uri.MatchType.Never -> DSecret.Uri.MatchType.Never
}

fun BitwardenCipher.Field.toDomain() = DSecret.Field(
    name = name,
    value = value,
    type = type.toDomain(),
    linkedId = linkedId?.toDomain(),
)

fun BitwardenCipher.Field.Type.toDomain() = when (this) {
    BitwardenCipher.Field.Type.Boolean -> DSecret.Field.Type.Boolean
    BitwardenCipher.Field.Type.Text -> DSecret.Field.Type.Text
    BitwardenCipher.Field.Type.Hidden -> DSecret.Field.Type.Hidden
    BitwardenCipher.Field.Type.Linked -> DSecret.Field.Type.Linked
}

fun BitwardenCipher.Field.LinkedId.toDomain() = when (this) {
    BitwardenCipher.Field.LinkedId.Login_Username -> DSecret.Field.LinkedId.Login_Username
    BitwardenCipher.Field.LinkedId.Login_Password -> DSecret.Field.LinkedId.Login_Password
    BitwardenCipher.Field.LinkedId.Card_CardholderName -> DSecret.Field.LinkedId.Card_CardholderName
    BitwardenCipher.Field.LinkedId.Card_ExpMonth -> DSecret.Field.LinkedId.Card_ExpMonth
    BitwardenCipher.Field.LinkedId.Card_ExpYear -> DSecret.Field.LinkedId.Card_ExpYear
    BitwardenCipher.Field.LinkedId.Card_Code -> DSecret.Field.LinkedId.Card_Code
    BitwardenCipher.Field.LinkedId.Card_Brand -> DSecret.Field.LinkedId.Card_Brand
    BitwardenCipher.Field.LinkedId.Card_Number -> DSecret.Field.LinkedId.Card_Number
    BitwardenCipher.Field.LinkedId.Identity_Title -> DSecret.Field.LinkedId.Identity_Title
    BitwardenCipher.Field.LinkedId.Identity_MiddleName -> DSecret.Field.LinkedId.Identity_MiddleName
    BitwardenCipher.Field.LinkedId.Identity_Address1 -> DSecret.Field.LinkedId.Identity_Address1
    BitwardenCipher.Field.LinkedId.Identity_Address2 -> DSecret.Field.LinkedId.Identity_Address2
    BitwardenCipher.Field.LinkedId.Identity_Address3 -> DSecret.Field.LinkedId.Identity_Address3
    BitwardenCipher.Field.LinkedId.Identity_City -> DSecret.Field.LinkedId.Identity_City
    BitwardenCipher.Field.LinkedId.Identity_State -> DSecret.Field.LinkedId.Identity_State
    BitwardenCipher.Field.LinkedId.Identity_PostalCode -> DSecret.Field.LinkedId.Identity_PostalCode
    BitwardenCipher.Field.LinkedId.Identity_Country -> DSecret.Field.LinkedId.Identity_Country
    BitwardenCipher.Field.LinkedId.Identity_Company -> DSecret.Field.LinkedId.Identity_Company
    BitwardenCipher.Field.LinkedId.Identity_Email -> DSecret.Field.LinkedId.Identity_Email
    BitwardenCipher.Field.LinkedId.Identity_Phone -> DSecret.Field.LinkedId.Identity_Phone
    BitwardenCipher.Field.LinkedId.Identity_Ssn -> DSecret.Field.LinkedId.Identity_Ssn
    BitwardenCipher.Field.LinkedId.Identity_Username -> DSecret.Field.LinkedId.Identity_Username
    BitwardenCipher.Field.LinkedId.Identity_PassportNumber -> DSecret.Field.LinkedId.Identity_PassportNumber
    BitwardenCipher.Field.LinkedId.Identity_LicenseNumber -> DSecret.Field.LinkedId.Identity_LicenseNumber
    BitwardenCipher.Field.LinkedId.Identity_FirstName -> DSecret.Field.LinkedId.Identity_FirstName
    BitwardenCipher.Field.LinkedId.Identity_LastName -> DSecret.Field.LinkedId.Identity_LastName
    BitwardenCipher.Field.LinkedId.Identity_FullName -> DSecret.Field.LinkedId.Identity_FullName
}

fun BitwardenCipher.Attachment.Remote.toDomain(
    remoteCipherId: String,
) = DSecret.Attachment.Remote(
    id = id,
    remoteCipherId = remoteCipherId,
    url = url,
    fileName = fileName,
    keyBase64 = keyBase64,
    size = size,
)

fun BitwardenCipher.Attachment.Local.toDomain() = DSecret.Attachment.Local(
    id = id,
    url = url,
    fileName = fileName,
    size = size,
)

suspend fun BitwardenCipher.Login.toDomain(
    getPasswordStrength: GetPasswordStrength,
) = DSecret.Login(
    username = username,
    password = password,
    passwordStrength = passwordStrength?.toDomain() ?: password?.let {
        getPasswordStrength(it).attempt().bind().getOrNull()
    },
    passwordRevisionDate = passwordRevisionDate,
    passwordHistory = passwordHistory.map(BitwardenCipher.Login.PasswordHistory::toDomain),
    totp = totp
        ?.let { raw ->
            TotpToken
                .parse(raw)
                .map { token ->
                    DSecret.Login.Totp(
                        raw = raw,
                        token = token,
                    )
                }
                .getOrNull()
        },
    fido2Credentials = fido2Credentials
        .map { credentials ->
            val counter = credentials.counter
                .toIntOrNull()
            val discoverable = credentials.discoverable.toBoolean()
            DSecret.Login.Fido2Credentials(
                credentialId = credentials.credentialId
                    // It should never be empty, as it doesn't really make sense.
                    // An empty credential ID should not be accepted by the server.
                    .orEmpty(),
                keyType = credentials.keyType,
                keyAlgorithm = credentials.keyAlgorithm,
                keyCurve = credentials.keyCurve,
                keyValue = credentials.keyValue,
                rpId = credentials.rpId,
                rpName = credentials.rpName,
                counter = counter,
                userHandle = credentials.userHandle,
                userName = credentials.userName,
                userDisplayName = credentials.userDisplayName,
                discoverable = discoverable,
                creationDate = credentials.creationDate,
            )
        },
)

fun BitwardenCipher.Login.PasswordHistory.toDomain() = DSecret.Login.PasswordHistory(
    password = password,
    // Bitwarden forces us to have a last used date for
    // the password history item. The ones that had it as
    // null will end up converted to the zero Unix timestamp.
    lastUsedDate = lastUsedDate
        ?.takeUnless { it.epochSeconds == 0L },
)

fun BitwardenCipher.Login.PasswordStrength.toDomain() = PasswordStrength(
    crackTimeSeconds = crackTimeSeconds,
    version = version,
)

fun BitwardenCipher.Card.toDomain() = DSecret.Card(
    cardholderName = cardholderName.oh(),
    brand = brand.oh(),
    number = number.oh(),
    expMonth = expMonth.oh(),
    expYear = expYear.oh(),
    code = code.oh(),
)

fun BitwardenCipher.Identity.toDomain() = DSecret.Identity(
    title = title.oh(),
    firstName = firstName.oh(),
    middleName = middleName.oh(),
    lastName = lastName.oh(),
    address1 = address1.oh(),
    address2 = address2.oh(),
    address3 = address3.oh(),
    city = city.oh(),
    state = state.oh(),
    postalCode = postalCode.oh(),
    country = country.oh(),
    company = company.oh(),
    email = email.oh(),
    phone = phone.oh(),
    ssn = ssn.oh(),
    username = username.oh(),
    passportNumber = passportNumber.oh(),
    licenseNumber = licenseNumber.oh(),
)

private fun String?.oh() = this?.takeIf { it.isNotBlank() }
