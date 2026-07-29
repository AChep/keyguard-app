package com.artemchep.keyguard.common.service.credentialexchange

import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.impl.CXF_CARD_VALID_FROM_LABEL
import com.artemchep.keyguard.common.service.credentialexchange.impl.CXF_TOTP_DIGITS_RANGE
import com.artemchep.keyguard.common.service.credentialexchange.impl.CXF_TOTP_PERIOD_RANGE_LONG
import com.artemchep.keyguard.common.service.credentialexchange.impl.CxfIdentityOverflowLabels
import com.artemchep.keyguard.common.service.credentialexchange.impl.DEFAULT_FIELD_LABEL
import com.artemchep.keyguard.common.service.credentialexchange.impl.canonicalTotpSecretOrNull
import com.artemchep.keyguard.common.service.credentialexchange.impl.mapTotpAlgorithm
import com.artemchep.keyguard.common.service.credentialexchange.impl.yearMonthOrNull
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import kotlin.time.Instant

private const val COMMAND_PREFIX = "cmd://"
private const val SHA256_FINGERPRINT_BYTES = 32

/**
 * Projects a vault item onto the shape the CXF round trip can return it in.
 *
 * Every transform here is one of exactly four kinds, tagged (1)–(4) at each use;
 * that is the line between this file and a second copy of the production mapper:
 *
 *  1. ERASURE     — a member becomes null / false / empty / a fixed constant,
 *                   because the wire format has nowhere to put it.
 *  2. SELECTION   — a collection is filtered or truncated by a *predicate*.
 *  3. REORDERING  — a collection is re-ordered by a *declared* order.
 *  4. RELOCATION  — a value moves, verbatim, from one member to another.
 *
 * Recomputing a value is out of scope — base64, hex, `YYYY-MM` packing, otpauth
 * uri building, base32 canonicalisation, address-line joining — or the assertion
 * becomes a copy of the code under test. Those spellings are instead required to
 * be canonical in the fixture ([cxfFido2Credential], [cxfTotpAuth] and
 * [cxfCertFingerprint] default to canonical values), and every non-canonical
 * form is pinned by a focused test in `CxfRoundTripEncodingTest` with a literal
 * expected value.
 *
 * It is *restating* a spelling that is banned, not borrowing one: where a value
 * is unavoidable the production function that produced it is imported —
 * `canonicalTotpSecretOrNull` for a totp secret, `yearMonthOrNull` for the
 * card's travelling `validFrom` — so the two directions cannot drift apart, and
 * the encoding itself is still pinned separately with a literal.
 *
 * The one admitted exception is the card month/year integer re-print (`"05"` →
 * `"5"`), which is numeric identity rather than a format encoding; the
 * two-digit-year *shift* (`"25"` → `"2025"`) is a format decision, so this
 * normalizer requires four-digit years.
 */
internal fun DSecret.toCxfRoundTripView(
    now: Instant,
    folderTitle: String? = null,
): CxfRoundTripView {
    // (1) A trashed or archived item is not exported at all, and an item with
    // nothing representable comes back as nothing — all three are the empty
    // view. Trash and archive are both withheld at the item level, so neither
    // reaches the payload; the difference between them is only whether the
    // review screen counts it, which the harness asserts separately.
    val carried = carriedPayload(now)
        .takeIf { deletedDate == null && archivedDate == null }
    val types = carried?.expectedTypes().orEmpty()
    if (carried == null || types.isEmpty()) {
        return CxfRoundTripView()
    }
    return CxfRoundTripView(
        types = types,
        titles = types.associateWith { type ->
            // (4) A card with no title borrows the cardholder's name.
            val fallback = carried.card?.cardholderName.takeIf { type == DSecret.Type.Card }
            name.ifBlank { null } ?: fallback
        },
        uris = types.associateWith { type ->
            if (type == DSecret.Type.Login) carried.loginUris else emptyList()
        },
        fields = carried.fieldsByType(types),
        note = carried.note,
        favorite = favorite,
        tags = tags,
        folderTitle = folderTitle,
        login = carried.login,
        passkeys = carried.passkeys,
        card = carried.card,
        identity = carried.identity,
        sshKey = carried.sshKey,
    )
}

/**
 * The already-normalized payload of one item. [expectedTypes] reads *this*
 * rather than the source `DSecret`, so the type inference cannot drift into a
 * second copy of `collectCredentials`.
 */
private class CarriedPayload(
    val login: CxfRoundTripLoginView?,
    val passkeys: List<DSecret.Login.Fido2Credentials>,
    val loginUris: List<DSecret.Uri>,
    val card: CreateRequest.Card?,
    val identity: CreateRequest.Identity?,
    val sshKey: CreateRequest.SshKey?,
    val note: String?,
    val cardFields: List<DSecret.Field>,
    val identityFields: List<DSecret.Field>,
    val leftoverFields: List<DSecret.Field>,
) {
    /**
     * (3) The types the item comes back as. `DSecret.type` is NOT on the wire:
     * the importer infers the shape from the credential mix, which is why a
     * Login-typed item carrying only notes returns as a secure note, and why an
     * item carrying a login *and* a card *and* an identity returns as three.
     */
    fun expectedTypes(): List<DSecret.Type> {
        val produced = REQUEST_ORDER.filter { type ->
            when (type) {
                DSecret.Type.Login -> login != null
                DSecret.Type.Card -> card != null
                DSecret.Type.Identity -> identity != null
                DSecret.Type.SshKey -> sshKey != null
                else -> false
            }
        }
        if (produced.isNotEmpty()) {
            return produced
        }
        val standalone = note != null || leftoverFields.isNotEmpty() || identityFields.isNotEmpty()
        return if (standalone) listOf(DSecret.Type.SecureNote) else emptyList()
    }

    /**
     * (3) The card's and the identity's own overflow fields sit on their own
     * request; everything left over is appended to whichever request came first.
     */
    fun fieldsByType(types: List<DSecret.Type>): Map<DSecret.Type, List<DSecret.Field>> {
        val result = types.associateWith { emptyList<DSecret.Field>() }.toMutableMap()
        if (DSecret.Type.Card in types) {
            result[DSecret.Type.Card] = cardFields
        }
        if (DSecret.Type.Identity in types) {
            result[DSecret.Type.Identity] = identityFields
        }
        val firstType = types.first()
        result[firstType] = result[firstType].orEmpty() + leftoverFields
        return result
    }

    private companion object {
        val REQUEST_ORDER = listOf(
            DSecret.Type.Login,
            DSecret.Type.Card,
            DSecret.Type.Identity,
            DSecret.Type.SshKey,
        )
    }
}

private fun DSecret.carriedPayload(now: Instant): CarriedPayload {
    val passkeys = normalizedPasskeys(now)
    val scopeUris = uris.normalizeScope()
    val absorbed = absorbOverflowFields(identity, fields.exportableFields())
    val totp = login?.let { it.totp?.token?.normalizeTotp(it.username) }
    val basicUsername = login?.username?.ifBlank { null }
        // (4) With no basic-auth username, the first passkey's lends its own.
        ?: passkeys.firstNotNullOfOrNull { it.userName }
    // (1) An *empty* password is not a password and neither side carries it. A
    // whitespace-only one is: both mappers gate on `isNotEmpty`, so it survives
    // the crossing verbatim and must not be erased here.
    val password = login?.password?.ifEmpty { null }
    val hasLogin = basicUsername != null || password != null || totp != null || passkeys.isNotEmpty()
    return CarriedPayload(
        login = CxfRoundTripLoginView(
            username = basicUsername,
            password = password,
            totp = totp,
        ).takeIf { hasLogin },
        passkeys = passkeys,
        loginUris = scopeUris.ifEmpty {
            // (4) With no scope at all, a passkey's rpId becomes a uri.
            passkeys.firstNotNullOfOrNull { it.rpId.ifBlank { null } }
                ?.let { listOf(DSecret.Uri(uri = "https://$it")) }
                .orEmpty()
        },
        card = card?.normalizeCard(),
        identity = absorbed.identity?.normalizeIdentity(),
        sshKey = sshKey
            ?.takeIf { it.isExportable }
            // A placeholder the harness swaps for the seam's own output.
            ?.let { CreateRequest.SshKey() },
        note = notes.ifBlank { null },
        cardFields = card?.validFromFields().orEmpty(),
        identityFields = absorbed.identityFields,
        leftoverFields = absorbed.leftoverFields,
    )
}

// region (1) Erasure and (2) selection on collections

/**
 * (1) `match` is never on the wire, and only an android-app uri's *first*
 * signature is representable. (2) A signature the importer cannot use — not
 * exactly 32 bytes — is dropped while its app entry survives.
 *
 * The 32-byte filter mirrors `mapCertificateFingerprint`'s own gate; it is still
 * needed because fixtures feed this normalizer directly and may carry junk.
 */
private fun DSecret.Uri.normalize(): DSecret.Uri = copy(
    uri = normalizedUriText(),
    match = null,
    signatures = signatures
        .takeIf { isAndroidApp }
        .orEmpty()
        .take(1)
        .filter { it.certFingerprintSha256.isCanonicalSha256Fingerprint() },
)

/**
 * (3) Urls are emitted before android apps, so an interleaved list comes back
 * partitioned. (2) Anything the scope cannot carry is removed first, which is
 * what shifts the remaining positions.
 */
private fun List<DSecret.Uri>.normalizeScope(): List<DSecret.Uri> {
    val (androidApps, urls) = filter { it.isScope }
        .map { it.normalize() }
        .partition { it.isAndroidApp }
    return urls + androidApps
}

private val DSecret.Uri.isAndroidApp: Boolean
    get() = uri.trim().startsWith(PROTOCOL_ANDROID_APP, ignoreCase = true)

private val DSecret.Uri.isScope: Boolean
    get() {
        val text = uri.trim()
        val bundleId = text.removePrefixIgnoringCase(PROTOCOL_ANDROID_APP)
        return text.isNotEmpty() &&
            match != DSecret.Uri.MatchType.RegularExpression &&
            !text.startsWith(COMMAND_PREFIX, ignoreCase = true) &&
            !(isAndroidApp && bundleId.isBlank())
    }

/**
 * (1) The text is trimmed on the way out and the `androidapp://` scheme comes
 * back in its canonical lower case.
 */
private fun DSecret.Uri.normalizedUriText(): String {
    val text = uri.trim()
    return if (isAndroidApp) {
        PROTOCOL_ANDROID_APP + text.removePrefixIgnoringCase(PROTOCOL_ANDROID_APP)
    } else {
        text
    }
}

private fun String.removePrefixIgnoringCase(prefix: String): String =
    if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

private fun String.isCanonicalSha256Fingerprint(): Boolean =
    replace(":", "").length == SHA256_FINGERPRINT_BYTES * 2

/**
 * (2) A `Linked` field has no CXF representation, and a field with no value is
 * dropped on the way in or out. (1) `linkedId` is erased.
 */
private fun List<DSecret.Field>.exportableFields(): List<DSecret.Field> = this
    .filter { it.type != DSecret.Field.Type.Linked }
    .filter { !it.value.isNullOrBlank() }
    .map { field ->
        field.copy(
            // (1) A field with no name is stored under a generic one.
            name = field.name?.ifBlank { null } ?: DEFAULT_FIELD_LABEL,
            linkedId = null,
        )
    }

// endregion

// region (1) Passkeys

/**
 * (2) A passkey with a non-zero counter or unsupported key metadata is excluded
 * by §3.3.12. The item's own creation date is (4) relocated onto the credential.
 */
private fun DSecret.normalizedPasskeys(now: Instant): List<DSecret.Login.Fido2Credentials> =
    login?.fido2Credentials
        .orEmpty()
        .filter { (it.counter ?: 0) == 0 }
        .filter {
            it.keyType == "public-key" &&
                    it.keyAlgorithm == "ECDSA" &&
                    it.keyCurve == "P-256"
        }
        .map { passkey ->
            passkey.copy(
                rpName = null,
                discoverable = true,
                counter = 0,
                // (4) The item's creation date, truncated to whole seconds.
                creationDate = createdDate?.let { Instant.fromEpochSeconds(it.epochSeconds) } ?: now,
                // (4) The two names fall back to each other, in both directions;
                // a value that ends up blank on the wire returns as null.
                userName = pickNonBlank(passkey.userName, passkey.userDisplayName),
                userDisplayName = pickNonBlank(passkey.userDisplayName, passkey.userName),
            )
        }

private fun pickNonBlank(primary: String?, fallback: String?): String? =
    primary?.takeIf { it.isNotBlank() } ?: fallback?.takeIf { it.isNotBlank() }

// endregion

// region (2) TOTP

/**
 * (2) Only a token the shared CXF TOTP contract admits survives: a
 * representable algorithm, a period and digit count inside the shared ranges,
 * and a base32 secret. The predicates are imported from production rather than
 * restated, so the two directions cannot drift apart.
 */
private fun TotpToken.normalizeTotp(fallbackUsername: String?): CxfRoundTripTotpView? =
    when (this) {
        is TotpToken.TotpAuth -> normalizeTotpAuth(fallbackUsername)
        is TotpToken.SteamAuth -> normalizeSteamAuth()
        else -> null
    }

private fun TotpToken.TotpAuth.normalizeTotpAuth(
    fallbackUsername: String?,
): CxfRoundTripTotpView? {
    val representable = mapTotpAlgorithm(algorithm) != null &&
        period in CXF_TOTP_PERIOD_RANGE_LONG &&
        digits in CXF_TOTP_DIGITS_RANGE
    val secret = canonicalTotpSecretOrNull(keyBase32)
    if (!representable || secret == null) {
        return null
    }
    return CxfRoundTripTotpView(
        secretBase32 = secret,
        digits = digits,
        period = period,
        algorithm = algorithm,
        issuer = issuer,
        // (4) The login's username back-fills the token's, then travels in the
        // otpauth label and comes back parsed out of it.
        username = username ?: fallbackUsername?.takeIf { it.isNotBlank() },
    )
}

/**
 * (1) A Steam token has no issuer and no username of its own, and the wire
 * `username` the exporter fills in from the enclosing login cannot come back:
 * `steam://` carries only the secret. (1) The algorithm is likewise erased to
 * SHA-1, the value `parseOtpSteam` always constructs — a Steam code is not an
 * HMAC choice the user makes.
 */
private fun TotpToken.SteamAuth.normalizeSteamAuth(): CxfRoundTripTotpView? {
    val secret = canonicalTotpSecretOrNull(keyBase32)
        ?: return null
    return CxfRoundTripTotpView(
        secretBase32 = secret,
        digits = TotpToken.SteamAuth.DIGITS,
        period = TotpToken.SteamAuth.PERIOD,
        algorithm = CryptoHashAlgorithm.SHA_1,
        steam = true,
    )
}

// endregion

// region (1) Card and identity

/**
 * (2) A year-month is lost as a unit when either half is unusable. The month is
 * re-printed as an integer, which is the one admitted recompute.
 *
 * (1) `fromMonth`/`fromYear` never come back as card members: `AddCipher` builds
 * `BitwardenCipher.Card` without them, so the importer routes the wire
 * `validFrom` into a labelled field instead — see [validFromFields]. The card
 * itself therefore survives as a non-null but all-default request whenever
 * that field is the only thing it carried, which is exactly what the importer
 * produces.
 */
private fun DSecret.Card.normalizeCard(): CreateRequest.Card? {
    val card = CreateRequest.Card(
        cardholderName = cardholderName?.ifBlank { null },
        brand = brand?.ifBlank { null },
        number = number?.ifBlank { null },
        code = code?.ifBlank { null },
        expMonth = asCardMonth(expMonth, expYear),
        expYear = asCardYear(expMonth, expYear),
    )
    return card.takeIf { it != CreateRequest.Card() || validFromFields().isNotEmpty() }
}

/**
 * (4) The card's validity start, relocated verbatim into the field the importer
 * parks it in.
 *
 * The `YYYY-MM` spelling is the exporter's own [yearMonthOrNull], imported
 * rather than restated: the importer copies the wire value into the field
 * untouched, so the only thing this needs is the string that actually travels —
 * not a second copy of the packing rule, and not the four-digit-year
 * restriction the expiry re-split has to impose.
 */
private fun DSecret.Card.validFromFields(): List<DSecret.Field> = listOfNotNull(
    yearMonthOrNull(month = fromMonth, year = fromYear)?.let { travelled ->
        DSecret.Field(
            name = CXF_CARD_VALID_FROM_LABEL,
            value = travelled.value,
            type = DSecret.Field.Type.Text,
        )
    },
)

private fun asCardMonth(month: String?, year: String?): String? =
    month.takeIf { yearMonthSurvives(month, year) }?.trim()?.toIntOrNull()?.toString()

private fun asCardYear(month: String?, year: String?): String? =
    year.takeIf { yearMonthSurvives(month, year) }?.trim()?.toIntOrNull()?.toString()

/**
 * (2) The predicate, without spelling out the `YYYY-MM` packing: a four-digit
 * year and a month in range. Two-digit years shift and are excluded from the
 * shared rule on purpose — see the file header.
 */
private fun yearMonthSurvives(month: String?, year: String?): Boolean {
    val monthValue = month?.trim()?.toIntOrNull()?.takeIf { it in 1..12 }
    val yearValue = year?.trim()?.toIntOrNull()?.takeIf { it in 1000..9999 }
    return monthValue != null && yearValue != null
}

private fun DSecret.Identity.normalizeIdentity(): CreateRequest.Identity? {
    // (3) The three address lines are joined and re-split positionally, so a
    // gap in the middle closes up.
    val lines = listOfNotNull(address1, address2, address3).filter { it.isNotBlank() }
    val identity = CreateRequest.Identity(
        title = title?.ifBlank { null },
        firstName = firstName?.ifBlank { null },
        middleName = middleName?.ifBlank { null },
        lastName = lastName?.ifBlank { null },
        address1 = lines.getOrNull(0),
        address2 = lines.getOrNull(1),
        address3 = lines.drop(2).takeIf { it.isNotEmpty() }?.joinToString(separator = ", "),
        city = city?.ifBlank { null },
        state = state?.ifBlank { null },
        postalCode = postalCode?.ifBlank { null },
        country = country?.ifBlank { null },
        phone = phone?.ifBlank { null },
        company = company?.ifBlank { null },
        email = email?.ifBlank { null },
        username = username?.ifBlank { null },
        ssn = ssn?.ifBlank { null },
        passportNumber = passportNumber?.ifBlank { null },
        licenseNumber = licenseNumber?.ifBlank { null },
    )
    return identity.takeIf { it != CreateRequest.Identity() }
}

// endregion

// region (4) Identity overflow

private class Absorbed(
    val identity: DSecret.Identity?,
    val identityFields: List<DSecret.Field>,
    val leftoverFields: List<DSecret.Field>,
)

private val OVERFLOW_SLOTS: Map<String, (DSecret.Identity) -> String?> = mapOf(
    CxfIdentityOverflowLabels.COMPANY to { it.company },
    CxfIdentityOverflowLabels.EMAIL to { it.email },
    CxfIdentityOverflowLabels.USERNAME to { it.username },
    CxfIdentityOverflowLabels.SSN to { it.ssn },
    CxfIdentityOverflowLabels.PASSPORT_NUMBER to { it.passportNumber },
    CxfIdentityOverflowLabels.LICENSE_NUMBER to { it.licenseNumber },
)

/**
 * Six identity members have no CXF slot of their own and travel as custom
 * fields labelled with [CxfIdentityOverflowLabels]. The importer pulls them
 * back by *exact* label match, and only into an empty slot — so a user field
 * literally named "Company" on an item whose company slot is empty is absorbed
 * into the identity and disappears from the field list.
 */
private fun absorbOverflowFields(
    identity: DSecret.Identity?,
    userFields: List<DSecret.Field>,
): Absorbed {
    if (identity == null) {
        return Absorbed(identity = null, identityFields = emptyList(), leftoverFields = userFields)
    }
    var absorbedIdentity: DSecret.Identity = identity
    val leftovers = userFields.filter { field ->
        val slot = field.name?.takeIf { it in OVERFLOW_SLOTS.keys }
        val value = field.value?.takeIf { it.isNotBlank() }
        if (slot == null || value == null) {
            return@filter true
        }
        // Only an empty slot absorbs; a collision leaves the field alone.
        val free = OVERFLOW_SLOTS.getValue(slot)(absorbedIdentity) == null
        if (free) {
            absorbedIdentity = absorbedIdentity.withOverflowSlot(slot, value)
        }
        !free
    }
    return Absorbed(
        identity = absorbedIdentity,
        // The identity's own overflow comes back as fields only when the slot
        // it belongs to was already taken; a free slot round-trips in place.
        identityFields = emptyList(),
        leftoverFields = leftovers,
    )
}

private fun DSecret.Identity.withOverflowSlot(
    label: String,
    value: String,
): DSecret.Identity = when (label) {
    CxfIdentityOverflowLabels.COMPANY -> copy(company = value)
    CxfIdentityOverflowLabels.EMAIL -> copy(email = value)
    CxfIdentityOverflowLabels.USERNAME -> copy(username = value)
    CxfIdentityOverflowLabels.SSN -> copy(ssn = value)
    CxfIdentityOverflowLabels.PASSPORT_NUMBER -> copy(passportNumber = value)
    CxfIdentityOverflowLabels.LICENSE_NUMBER -> copy(licenseNumber = value)
    else -> this
}

// endregion

/**
 * (2) An ssh key needs both halves to be exportable at all; the values
 * themselves cross a fake in both directions, so the view compares the *seam*
 * and not the key.
 */
private val DSecret.SshKey.isExportable: Boolean
    get() = !privateKey.isNullOrBlank() &&
        !publicKey?.trim()?.substringBefore(' ').isNullOrBlank()
