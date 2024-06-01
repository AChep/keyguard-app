@file:JvmName("DSecretFun")

package com.artemchep.keyguard.common.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.PermIdentity
import androidx.compose.ui.graphics.Color
import arrow.optics.optics
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.exists
import com.artemchep.keyguard.feature.auth.common.util.REGEX_EMAIL
import com.artemchep.keyguard.feature.auth.common.util.REGEX_PHONE_NUMBER
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canDelete
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canEdit
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.KeyguardNote
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant

@optics
data class DSecret(
    val id: String,
    val accountId: String,
    val folderId: String?,
    val organizationId: String?,
    val collectionIds: Set<String>,
    val revisionDate: Instant,
    val createdDate: Instant?,
    val deletedDate: Instant?,
    val service: BitwardenService,
    // common
    val name: String,
    val notes: String,
    val favorite: Boolean,
    val reprompt: Boolean,
    val synced: Boolean,
    val ignoredAlerts: Map<DWatchtowerAlertType, Instant> = emptyMap(),
    val uris: List<Uri> = emptyList(),
    val fields: List<Field> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    // types
    val type: Type,
    val login: Login? = null,
    val card: Card? = null,
    val identity: Identity? = null,
) : HasAccountId, HasCipherId {
    companion object {
        private const val ignoreLength = 3

        private val ignoreWords = setOf(
            // popular domains
            "com",
            "tk",
            "cn",
            "de",
            "net",
            "uk",
            "org",
            "nl",
            "icu",
            "ru",
            "eu",
            // rising domains
            "icu",
            "top",
            "xyz",
            "site",
            "online",
            "club",
            "wang",
            "vip",
            "shop",
            "work",
        )
    }

    val hasError = service.error.exists(revisionDate)

    val deleted: Boolean get() = deletedDate != null

    val accentLight: Color
    val accentDark: Color

    init {
        val colors = generateAccentColors(service.remote?.id ?: id)
        accentLight = colors.light
        accentDark = colors.dark
    }

    val favicon: FaviconUrl? = kotlin.run {
        val siteUrl = uris
            .firstOrNull { uri ->
                uri.uri.startsWith("http://") ||
                        uri.uri.startsWith("https://")
            }
            ?.uri
        siteUrl?.let {
            FaviconUrl(
                serverId = accountId,
                url = it,
            )
        }
    }

    data class SearchToken(
        val priority: Float,
        val value: String,
    )

    val tokens = kotlin.run {
        val out = mutableListOf<SearchToken>()
        // Split the name into tokens
        name.lowercase().replace("[\\.\\,\\[\\]\\!]".toRegex(), " ")
            .split(' ')
            .forEach {
                if (it.length <= ignoreLength || it in ignoreWords) {
                    return@forEach
                }
                out += SearchToken(
                    priority = 0.8f,
                    value = it,
                )
            }
        // Extract domain name from the username
        // if that looks like an email.
        if (login?.username != null) {
            val tokens = login.username.split("@")
            if (tokens.size == 2) { // this is an email
                val domain = tokens[1]
                domain
                    .lowercase()
                    .split('.')
                    .forEach {
                        if (it.length <= ignoreLength || it in ignoreWords) {
                            return@forEach
                        }
                        out += SearchToken(
                            priority = 1f,
                            value = it,
                        )
                    }
                // an email might contain extra info separated by the + sign
                val username = tokens[0]
                val extras = username.split('+')
                if (extras.size == 2) {
                    val extrasInfo = extras[1]
                    extrasInfo
                        .lowercase()
                        .split('.')
                        .forEach {
                            if (it.length <= ignoreLength || it in ignoreWords) {
                                return@forEach
                            }
                            out += SearchToken(
                                priority = 0.8f,
                                value = it,
                            )
                        }
                }
            }
        }
        out
    }

    override fun accountId(): String = accountId

    override fun cipherId(): String = id

    enum class Type {
        None,
        Login,
        SecureNote,
        Card,
        Identity,
    }

    sealed interface Attachment {
        val id: String
        val url: String?

        data class Remote(
            override val id: String,
            override val url: String?,
            val remoteCipherId: String,
            val fileName: String,
            val keyBase64: String?,
            val size: Long,
        ) : Attachment {
            companion object
        }

        data class Local(
            override val id: String,
            override val url: String,
            val fileName: String,
            val size: Long? = null,
        ) : Attachment {
            companion object
        }
    }

    @optics
    data class Field(
        val name: String? = null,
        val value: String? = null,
        val linkedId: LinkedId? = null,
        val type: Type,
    ) {
        companion object;

        enum class Type {
            Text,
            Hidden,
            Boolean,
            Linked,
        }

        enum class LinkedId(
            val type: DSecret.Type,
        ) {
            Login_Username(type = DSecret.Type.Login),
            Login_Password(type = DSecret.Type.Login),

            // card
            Card_CardholderName(type = DSecret.Type.Card),
            Card_ExpMonth(type = DSecret.Type.Card),
            Card_ExpYear(type = DSecret.Type.Card),
            Card_Code(type = DSecret.Type.Card),
            Card_Brand(type = DSecret.Type.Card),
            Card_Number(type = DSecret.Type.Card),

            // identity
            Identity_Title(type = DSecret.Type.Identity),
            Identity_MiddleName(type = DSecret.Type.Identity),
            Identity_Address1(type = DSecret.Type.Identity),
            Identity_Address2(type = DSecret.Type.Identity),
            Identity_Address3(type = DSecret.Type.Identity),
            Identity_City(type = DSecret.Type.Identity),
            Identity_State(type = DSecret.Type.Identity),
            Identity_PostalCode(type = DSecret.Type.Identity),
            Identity_Country(type = DSecret.Type.Identity),
            Identity_Company(type = DSecret.Type.Identity),
            Identity_Email(type = DSecret.Type.Identity),
            Identity_Phone(type = DSecret.Type.Identity),
            Identity_Ssn(type = DSecret.Type.Identity),
            Identity_Username(type = DSecret.Type.Identity),
            Identity_PassportNumber(type = DSecret.Type.Identity),
            Identity_LicenseNumber(type = DSecret.Type.Identity),
            Identity_FirstName(type = DSecret.Type.Identity),
            Identity_LastName(type = DSecret.Type.Identity),
            Identity_FullName(type = DSecret.Type.Identity),
        }
    }

    @optics
    data class Uri(
        val uri: String,
        val match: MatchType? = null,
    ) : LinkInfo {
        companion object;

        enum class MatchType {
            Domain,
            Host,
            StartsWith,
            Exact,
            RegularExpression,
            Never,
            ;

            companion object {
                val default = Domain
            }
        }
    }

    //
    // Types
    //

    @optics
    data class Login(
        val username: String? = null,
        val password: String? = null,
        val passwordStrength: PasswordStrength? = null,
        val passwordRevisionDate: Instant? = null,
        val passwordHistory: List<PasswordHistory> = emptyList(),
        val fido2Credentials: List<Fido2Credentials> = emptyList(),
        val totp: Totp? = null,
    ) {
        companion object;

        @optics
        data class PasswordHistory(
            val password: String,
            val lastUsedDate: Instant?,
        ) {
            companion object;

            val id = "$password|timestamp=$lastUsedDate"
        }

        @optics
        data class Totp(
            val raw: String,
            val token: TotpToken,
        ) {
            companion object
        }

        data class Fido2Credentials(
            val credentialId: String,
            val keyType: String, // public-key
            val keyAlgorithm: String, // ECDSA
            val keyCurve: String, // P-256
            val keyValue: String,
            val rpId: String,
            val rpName: String?,
            val counter: Int?,
            val userHandle: String,
            val userName: String?,
            val userDisplayName: String?,
            val discoverable: Boolean,
            val creationDate: Instant,
        )
    }

    @optics
    data class Card(
        val cardholderName: String? = null,
        val brand: String? = null,
        val number: String? = null,
        val fromMonth: String? = null,
        val fromYear: String? = null,
        val expMonth: String? = null,
        val expYear: String? = null,
        val code: String? = null,
    ) {
        companion object;

        val creditCardType = brand?.let(creditCards::firstOrNullByBrand)
            ?: number?.let(creditCards::firstOrNullByNumber)
    }

    @optics
    data class Identity(
        val title: String? = null,
        val firstName: String? = null,
        val middleName: String? = null,
        val lastName: String? = null,
        val address1: String? = null,
        val address2: String? = null,
        val address3: String? = null,
        val city: String? = null,
        val state: String? = null,
        val postalCode: String? = null,
        val country: String? = null,
        val company: String? = null,
        val email: String? = null,
        val phone: String? = null,
        val ssn: String? = null,
        val username: String? = null,
        val passportNumber: String? = null,
        val licenseNumber: String? = null,
    ) {
        companion object
    }
}

fun DSecret.ignores(alertType: DWatchtowerAlertType) = alertType in ignoredAlerts

fun DSecret.canDelete() = service.canDelete()

fun DSecret.canEdit() = service.canEdit()

fun DSecret.Attachment.fileName() = when (this) {
    is DSecret.Attachment.Local -> fileName
    is DSecret.Attachment.Remote -> fileName
}

fun DSecret.Attachment.fileSize() = when (this) {
    is DSecret.Attachment.Local -> size
    is DSecret.Attachment.Remote -> size
}

fun DSecret.Type.iconImageVector() = when (this) {
    DSecret.Type.Login -> Icons.Outlined.Password
    DSecret.Type.Card -> Icons.Outlined.CreditCard
    DSecret.Type.Identity -> Icons.Outlined.PermIdentity
    DSecret.Type.SecureNote -> Icons.Outlined.KeyguardNote
    DSecret.Type.None -> Icons.Stub
}

fun DSecret.Type.titleH() = when (this) {
    DSecret.Type.Login -> Res.string.cipher_type_login
    DSecret.Type.Card -> Res.string.cipher_type_card
    DSecret.Type.Identity -> Res.string.cipher_type_identity
    DSecret.Type.SecureNote -> Res.string.cipher_type_note
    DSecret.Type.None -> Res.string.cipher_type_unknown
}

fun DSecret.Uri.MatchType.titleH() = when (this) {
    DSecret.Uri.MatchType.Domain -> Res.string.uri_match_detection_domain_title
    DSecret.Uri.MatchType.Host -> Res.string.uri_match_detection_host_title
    DSecret.Uri.MatchType.StartsWith -> Res.string.uri_match_detection_startswith_title
    DSecret.Uri.MatchType.Exact -> Res.string.uri_match_detection_exact_title
    DSecret.Uri.MatchType.RegularExpression -> Res.string.uri_match_detection_regex_title
    DSecret.Uri.MatchType.Never -> Res.string.uri_match_detection_never_title
}

fun DSecret.Field.LinkedId.titleH() = when (this) {
    DSecret.Field.LinkedId.Login_Username -> Res.string.username
    DSecret.Field.LinkedId.Login_Password -> Res.string.password
    DSecret.Field.LinkedId.Card_CardholderName -> Res.string.cardholder_name
    DSecret.Field.LinkedId.Card_ExpMonth -> Res.string.card_expiry_month
    DSecret.Field.LinkedId.Card_ExpYear -> Res.string.card_expiry_year
    DSecret.Field.LinkedId.Card_Code -> Res.string.card_cvv
    DSecret.Field.LinkedId.Card_Brand -> Res.string.card_type
    DSecret.Field.LinkedId.Card_Number -> Res.string.card_number
    DSecret.Field.LinkedId.Identity_Title -> Res.string.identity_first_name
    DSecret.Field.LinkedId.Identity_MiddleName -> Res.string.identity_middle_name
    DSecret.Field.LinkedId.Identity_Address1 -> Res.string.address1
    DSecret.Field.LinkedId.Identity_Address2 -> Res.string.address2
    DSecret.Field.LinkedId.Identity_Address3 -> Res.string.address3
    DSecret.Field.LinkedId.Identity_City -> Res.string.city
    DSecret.Field.LinkedId.Identity_State -> Res.string.state
    DSecret.Field.LinkedId.Identity_PostalCode -> Res.string.postal_code
    DSecret.Field.LinkedId.Identity_Country -> Res.string.country
    DSecret.Field.LinkedId.Identity_Company -> Res.string.company
    DSecret.Field.LinkedId.Identity_Email -> Res.string.email
    DSecret.Field.LinkedId.Identity_Phone -> Res.string.phone_number
    DSecret.Field.LinkedId.Identity_Ssn -> Res.string.ssn
    DSecret.Field.LinkedId.Identity_Username -> Res.string.username
    DSecret.Field.LinkedId.Identity_PassportNumber -> Res.string.passport_number
    DSecret.Field.LinkedId.Identity_LicenseNumber -> Res.string.license_number
    DSecret.Field.LinkedId.Identity_FirstName -> Res.string.identity_first_name
    DSecret.Field.LinkedId.Identity_LastName -> Res.string.identity_last_name
    DSecret.Field.LinkedId.Identity_FullName -> Res.string.identity_full_name
}

fun DSecret.contains(hint: AutofillHint) = when (hint) {
    AutofillHint.EMAIL_ADDRESS -> login?.username != null && login.username.matches(REGEX_EMAIL)
    AutofillHint.USERNAME -> login?.username != null
    AutofillHint.PASSWORD -> login?.password != null
    AutofillHint.WIFI_PASSWORD -> false // not supported
    AutofillHint.POSTAL_ADDRESS ->
        identity?.address1 != null ||
                identity?.address2 != null ||
                identity?.address3 != null

    AutofillHint.POSTAL_CODE -> identity?.postalCode != null
    AutofillHint.CREDIT_CARD_NUMBER -> card?.number != null
    AutofillHint.CREDIT_CARD_SECURITY_CODE -> card?.code != null
    AutofillHint.CREDIT_CARD_EXPIRATION_DATE ->
        card?.expYear != null ||
                card?.expMonth != null

    AutofillHint.CREDIT_CARD_EXPIRATION_MONTH -> card?.expMonth != null
    AutofillHint.CREDIT_CARD_EXPIRATION_YEAR -> card?.expYear != null
    AutofillHint.CREDIT_CARD_EXPIRATION_DAY -> false // not supported
    AutofillHint.POSTAL_ADDRESS_COUNTRY -> identity?.country != null
    AutofillHint.POSTAL_ADDRESS_REGION -> false // not supported
    AutofillHint.POSTAL_ADDRESS_LOCALITY -> false // not supported
    AutofillHint.POSTAL_ADDRESS_STREET_ADDRESS -> identity?.address2 != null
    AutofillHint.POSTAL_ADDRESS_EXTENDED_ADDRESS -> false // not supported
    AutofillHint.POSTAL_ADDRESS_EXTENDED_POSTAL_CODE -> false // not supported
    AutofillHint.POSTAL_ADDRESS_APT_NUMBER -> identity?.address1 != null
    AutofillHint.POSTAL_ADDRESS_DEPENDENT_LOCALITY -> false // not supported
    AutofillHint.PERSON_NAME -> identity?.firstName != null
    AutofillHint.PERSON_NAME_GIVEN -> false // not supported
    AutofillHint.PERSON_NAME_FAMILY -> identity?.lastName != null
    AutofillHint.PERSON_NAME_MIDDLE -> identity?.middleName != null
    AutofillHint.PERSON_NAME_MIDDLE_INITIAL -> identity?.middleName != null
    AutofillHint.PERSON_NAME_PREFIX -> false // not supported
    AutofillHint.PERSON_NAME_SUFFIX -> false // not supported
    AutofillHint.PHONE_NUMBER -> identity?.phone != null
    AutofillHint.PHONE_NUMBER_DEVICE -> false // not supported
    AutofillHint.PHONE_COUNTRY_CODE -> identity?.phone != null // TODO: Extract country code
    AutofillHint.PHONE_NATIONAL -> false // not supported
    AutofillHint.NEW_USERNAME -> false // not supported
    AutofillHint.NEW_PASSWORD -> false // not supported
    AutofillHint.GENDER -> false // not supported
    AutofillHint.BIRTH_DATE_FULL -> false // not supported
    AutofillHint.BIRTH_DATE_DAY -> false // not supported
    AutofillHint.BIRTH_DATE_MONTH -> false // not supported
    AutofillHint.BIRTH_DATE_YEAR -> false // not supported
    AutofillHint.SMS_OTP -> false // not supported
    AutofillHint.EMAIL_OTP -> false // not supported
    AutofillHint.APP_OTP -> login?.totp != null
    AutofillHint.NOT_APPLICABLE -> false // not supported
    AutofillHint.PROMO_CODE -> false // not supported
    AutofillHint.UPI_VPA -> false // not supported
    AutofillHint.OFF -> false
}

fun DSecret.get(
    hint: AutofillHint,
    getTotpCode: GetTotpCode,
) = ioEffect {
    when (hint) {
        AutofillHint.EMAIL_ADDRESS -> login?.username
        AutofillHint.USERNAME -> login?.username
        AutofillHint.PASSWORD -> login?.password
        AutofillHint.WIFI_PASSWORD -> null
        AutofillHint.POSTAL_ADDRESS -> null
        AutofillHint.POSTAL_CODE -> identity?.postalCode
        AutofillHint.CREDIT_CARD_NUMBER -> card?.number
        AutofillHint.CREDIT_CARD_SECURITY_CODE -> card?.code
        AutofillHint.CREDIT_CARD_EXPIRATION_DATE -> null
        AutofillHint.CREDIT_CARD_EXPIRATION_MONTH -> card?.expMonth
        AutofillHint.CREDIT_CARD_EXPIRATION_YEAR -> card?.expYear
        AutofillHint.CREDIT_CARD_EXPIRATION_DAY -> null
        AutofillHint.POSTAL_ADDRESS_COUNTRY -> identity?.country
        AutofillHint.POSTAL_ADDRESS_REGION -> null
        AutofillHint.POSTAL_ADDRESS_LOCALITY -> null
        AutofillHint.POSTAL_ADDRESS_STREET_ADDRESS -> identity?.address2
        AutofillHint.POSTAL_ADDRESS_EXTENDED_ADDRESS -> null
        AutofillHint.POSTAL_ADDRESS_EXTENDED_POSTAL_CODE -> null
        AutofillHint.POSTAL_ADDRESS_APT_NUMBER -> identity?.address1
        AutofillHint.POSTAL_ADDRESS_DEPENDENT_LOCALITY -> null
        AutofillHint.PERSON_NAME -> identity?.firstName
        AutofillHint.PERSON_NAME_GIVEN -> null
        AutofillHint.PERSON_NAME_FAMILY -> identity?.lastName
        AutofillHint.PERSON_NAME_MIDDLE -> identity?.middleName
        AutofillHint.PERSON_NAME_MIDDLE_INITIAL -> identity?.middleName
        AutofillHint.PERSON_NAME_PREFIX -> null
        AutofillHint.PERSON_NAME_SUFFIX -> null
        AutofillHint.PHONE_NUMBER -> identity?.phone
            ?: login?.username?.takeIf { REGEX_PHONE_NUMBER.matches(it) }

        AutofillHint.PHONE_NUMBER_DEVICE -> null
        AutofillHint.PHONE_COUNTRY_CODE -> identity?.phone // TODO: Extract country code
        AutofillHint.PHONE_NATIONAL -> null
        AutofillHint.NEW_USERNAME -> null
        AutofillHint.NEW_PASSWORD -> null
        AutofillHint.GENDER -> null
        AutofillHint.BIRTH_DATE_FULL -> null
        AutofillHint.BIRTH_DATE_DAY -> null
        AutofillHint.BIRTH_DATE_MONTH -> null
        AutofillHint.BIRTH_DATE_YEAR -> null
        AutofillHint.SMS_OTP -> null
        AutofillHint.EMAIL_OTP -> null
        AutofillHint.APP_OTP -> login?.totp?.token?.let {
            getTotpCode(it)
                .map { it.code }
                .toIO()
                .bind()
        }

        AutofillHint.NOT_APPLICABLE -> null
        AutofillHint.PROMO_CODE -> null
        AutofillHint.UPI_VPA -> null
        AutofillHint.OFF -> null
    }
}

fun DSecret.gett(
    hints: Set<AutofillHint>,
    getTotpCode: GetTotpCode,
) = ioEffect {
    val hintsWithDepth = hints
        .map { hint ->
            val variants = zzMap[hint].orEmpty()
                .toMutableList()
                .apply {
                    val self = AutofillMatcher(
                        hint = hint,
                    )
                    add(0, self)
                }
            hint to variants
        }

    val out = mutableMapOf<AutofillHint, String>()
    do {
        var shouldLoop = false
        hintsWithDepth.forEach { (hint, variants) ->
            if (variants.isEmpty()) {
                return@forEach
            }

            val variant = variants.removeFirst()
            val value = get(
                        hint = variant.hint,
                        getTotpCode = getTotpCode,
                    ).attempt().bind().getOrNull()
            if (value.isNullOrEmpty()) {
                shouldLoop = shouldLoop || variants.isNotEmpty()
                return@forEach
            }

            out[hint] = value
            variants.clear()
        }
    } while (shouldLoop)
    out
}
