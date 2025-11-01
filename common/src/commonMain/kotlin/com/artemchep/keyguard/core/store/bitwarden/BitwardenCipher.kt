package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.Getter
import arrow.optics.optics
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil.DiffApplierByListValue
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil.DiffFinderNode
import com.artemchep.keyguard.common.service.text.Base64Service
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

@Serializable
@optics(
    [
        arrow.optics.OpticsTarget.LENS,
        arrow.optics.OpticsTarget.OPTIONAL,
    ],
)
data class BitwardenCipher(
    /**
     * Id of the bitwarden account, generated on
     * login.
     */
    val accountId: String,
    val cipherId: String,
    val folderId: String? = null,
    val organizationId: String? = null,
    val collectionIds: Set<String> = emptySet(),
    val revisionDate: Instant,
    val createdDate: Instant? = null,
    val deletedDate: Instant? = null,
    val expiredDate: Instant? = null,
    // service fields
    override val service: BitwardenService,
    /**
     * If available, contains the entity as last seen
     * by the server. Used to resolve merge conflicts.
     */
    val remoteEntity: BitwardenCipher? = null,
    // common
    val keyBase64: String? = null,
    val name: String?,
    val notes: String?,
    val favorite: Boolean,
    val ignoredAlerts: Map<IgnoreAlertType, IgnoreAlertData> = emptyMap(),
    val tags: List<Tag> = emptyList(),
    val fields: List<Field> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val mapping: Map<String, String> = emptyMap(),
    val reprompt: RepromptType,
    // types
    val type: Type,
    val login: Login? = null,
    val secureNote: SecureNote? = null,
    val card: Card? = null,
    val identity: Identity? = null,
    val sshKey: SshKey? = null,
) : BitwardenService.Has<BitwardenCipher> {
    companion object;

    override fun withService(service: BitwardenService) = copy(service = service)

    enum class Mapping(
        val key: String,
    ) {
        LOGIN_USERNAME("login_username"),
        LOGIN_PASSWORD("login_password"),
        LOGIN_PASSWORD_REV_DATE("login_password_rev_date"),
        LOGIN_OTP("login_otp"),

        // Card
        CARD_CARDHOLDER_NAME(key = "card_cardholder_name"),
        CARD_BRAND(key = "card_brand"),
        CARD_NUMBER(key = "card_number"),
        CARD_EXP_MONTH(key = "card_exp_month"),
        CARD_EXP_YEAR(key = "card_exp_year"),
        CARD_CODE(key = "card_code"),

        // Identity
        IDENTITY_TITLE(key = "identity_title"),
        IDENTITY_USERNAME(key = "identity_username"),
        IDENTITY_FIRST_NAME(key = "identity_first_name"),
        IDENTITY_MIDDLE_NAME(key = "identity_middle_name"),
        IDENTITY_LAST_NAME(key = "identity_last_name"),
        IDENTITY_ADDRESS1(key = "identity_address1"),
        IDENTITY_ADDRESS2(key = "identity_address2"),
        IDENTITY_ADDRESS3(key = "identity_address3"),
        IDENTITY_CITY(key = "identity_city"),
        IDENTITY_STATE(key = "identity_state"),
        IDENTITY_POSTAL_CODE(key = "identity_postal_code"),
        IDENTITY_COUNTRY(key = "identity_country"),
        IDENTITY_COMPANY(key = "identity_company"),
        IDENTITY_EMAIL(key = "identity_email"),
        IDENTITY_PHONE(key = "identity_phone"),
        IDENTITY_SSN(key = "identity_ssn"),
        IDENTITY_PASSPORT_NUMBER(key = "identity_passport_number"),
        IDENTITY_LICENSE_NUMBER(key = "identity_license_number"),

        // SSH Key
        SSH_PRIVATE_KEY(key = "ssh_private_key"),
        SSH_PUBLIC_KEY(key = "ssh_public_key"),
        SSH_FINGERPRINT(key = "ssh_fingerprint"),
    }

    @Serializable
    enum class Type(
        val verboseKey: String,
    ) {
        Login("Login"),
        SecureNote("Note"),
        Card("Card"),
        Identity("Identity"),
        SshKey("SSH Key"),
    }

    @Serializable
    sealed interface Attachment {
        companion object;

        val id: String
        val url: String?

        @Serializable
        @SerialName("remote")
        data class Remote(
            override val id: String,
            override val url: String?,
            val fileName: String,
            val keyBase64: String? = null,
            val size: Long,
        ) : Attachment {
            companion object
        }

        @Serializable
        @SerialName("local")
        data class Local(
            override val id: String,
            override val url: String,
            val fileName: String,
            val size: Long? = null,
        ) : Attachment {
            companion object
        }
    }

    @Serializable
    data class Tag(
        val name: String,
    )

    @Serializable
    data class Field(
        val name: String? = null,
        val value: String? = null,
        val linkedId: LinkedId? = null,
        val type: Type,
    ) {
        @Serializable
        enum class Type {
            Text,
            Hidden,
            Boolean,
            Linked,
        }

        @Serializable
        enum class LinkedId {
            Login_Username,
            Login_Password,

            // card
            Card_CardholderName,
            Card_ExpMonth,
            Card_ExpYear,
            Card_Code,
            Card_Brand,
            Card_Number,

            // identity
            Identity_Title,
            Identity_MiddleName,
            Identity_Address1,
            Identity_Address2,
            Identity_Address3,
            Identity_City,
            Identity_State,
            Identity_PostalCode,
            Identity_Country,
            Identity_Company,
            Identity_Email,
            Identity_Phone,
            Identity_Ssn,
            Identity_Username,
            Identity_PassportNumber,
            Identity_LicenseNumber,
            Identity_FirstName,
            Identity_LastName,
            Identity_FullName,
            ;

            companion object
        }
    }

    @Serializable
    enum class RepromptType {
        None,
        Password,
    }

    @Serializable
    enum class IgnoreAlertType {
        @SerialName("reusedPassword")
        REUSED_PASSWORD,
        @SerialName("pwnedPassword")
        PWNED_PASSWORD,
        @SerialName("pwnedWebsite")
        PWNED_WEBSITE,
        @SerialName("unsecureWebsite")
        UNSECURE_WEBSITE,
        @SerialName("twoFaWebsite")
        TWO_FA_WEBSITE,
        @SerialName("passkeyWebsite")
        PASSKEY_WEBSITE,
        @SerialName("duplicate")
        DUPLICATE,
        @SerialName("duplicate_uris")
        DUPLICATE_URIS,
        @SerialName("broad_uris")
        BROAD_URIS,
        @SerialName("incomplete")
        INCOMPLETE,
        @SerialName("expiring")
        EXPIRING,
    }

    @Serializable
    data class IgnoreAlertData(
        val createdAt: Instant,
    )

    //
    // Types
    //

    @optics
    @Serializable
    data class Login(
        val username: String? = null,
        val password: String? = null,
        val passwordStrength: PasswordStrength? = null,
        val passwordRevisionDate: Instant? = null,
        val passwordHistory: List<PasswordHistory> = emptyList(),
        val uris: List<Uri>,
        val fido2Credentials: List<Fido2Credentials> = emptyList(),
        val totp: String? = null,
    ) {
        companion object;

        @Serializable
        data class PasswordHistory(
            val password: String,
            val lastUsedDate: Instant? = null,
        ) {
            val id = kotlin.run {
                val timestamp = lastUsedDate
                    ?.takeUnless { it.epochSeconds == 0L }
                "$password|timestamp=$timestamp"
            }
        }

        @Serializable
        data class PasswordStrength(
            val password: String,
            val crackTimeSeconds: Long,
            val version: Long,
        )

        @optics
        @Serializable
        data class Uri(
            val uri: String? = null,
            val uriChecksumBase64: String? = null,
            val match: MatchType? = null,
        ) {
            companion object;

            @Serializable
            enum class MatchType(
                val verboseKey: String,
            ) {
                Domain(verboseKey = "Domain"),
                Host(verboseKey = "Host"),
                StartsWith(verboseKey = "Starts With"),
                Exact(verboseKey = "Exact"),
                RegularExpression(verboseKey = "Regular Expression"),
                Never(verboseKey = "Never"),
            }
        }

        @Serializable
        data class Fido2Credentials(
            val credentialId: String? = null,
            val keyType: String,
            val keyAlgorithm: String,
            val keyCurve: String,
            val keyValue: String,
            val rpId: String,
            val rpName: String?,
            val counter: String,
            val userHandle: String,
            val userName: String? = null,
            val userDisplayName: String? = null,
            val discoverable: String,
            val creationDate: Instant,
        )
    }

    @optics
    @Serializable
    data class Card(
        val cardholderName: String? = null,
        val brand: String? = null,
        val number: String? = null,
        val expMonth: String? = null,
        val expYear: String? = null,
        val code: String? = null,
    ) {
        companion object
    }

    @optics
    @Serializable
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

    @Serializable
    data class SecureNote(
        val type: Type = Type.Generic,
    ) {
        companion object

        @Serializable
        enum class Type {
            Generic,
        }
    }

    @Serializable
    data class SshKey(
        val privateKey: String? = null,
        val publicKey: String? = null,
        val fingerprint: String? = null,
    ) {
        companion object
    }
}

/**
 * Returns the [DiffFinderNode.Group] merge rules, using which you can combine
 * ciphers together potentially not loosing any data.
 */
fun BitwardenCipher.Companion.getMergeRules() = kotlin.run {
    DiffFinderNode.Group<BitwardenCipher, BitwardenCipher>(
        lens = Getter.id(),
        identity = {
            val msg = "Can not merge ciphers some of which are null. " +
                    "This is not something that should happen!"
            throw IllegalStateException(msg)
        },
        children = listOf(
            DiffFinderNode.Leaf(BitwardenCipher.name),
            DiffFinderNode.Leaf(BitwardenCipher.notes),
            DiffFinderNode.Leaf(BitwardenCipher.favorite),
            DiffFinderNode.Leaf(
                lens = BitwardenCipher.fields,
                finder = DiffApplierByListValue(),
            ),
            DiffFinderNode.Leaf(
                lens = BitwardenCipher.tags,
                finder = DiffApplierByListValue(),
            ),
            // Types
            DiffFinderNode.Group(
                lens = BitwardenCipher.login,
                identity = {
                    BitwardenCipher.Login(
                        uris = emptyList(),
                    )
                },
                children = listOf(
                    DiffFinderNode.Leaf(BitwardenCipher.Login.username),
                    DiffFinderNode.Leaf(BitwardenCipher.Login.totp),
                    // FIXME: Use the second login merger to
                    //  merge these two fields at once.
                    DiffFinderNode.Leaf(BitwardenCipher.Login.password),
                    DiffFinderNode.Leaf(BitwardenCipher.Login.passwordRevisionDate),
                    DiffFinderNode.Leaf(
                        lens = BitwardenCipher.Login.uris,
                        finder = DiffApplierByListValue(),
                    ),
                    DiffFinderNode.Leaf(
                        lens = BitwardenCipher.Login.fido2Credentials,
                        finder = DiffApplierByListValue(),
                    ),
                ),
            ),
            DiffFinderNode.Group(
                lens = BitwardenCipher.card,
                identity = {
                    BitwardenCipher.Card(
                    )
                },
                children = listOf(
                    DiffFinderNode.Leaf(BitwardenCipher.Card.cardholderName),
                    DiffFinderNode.Leaf(BitwardenCipher.Card.brand),
                    DiffFinderNode.Leaf(BitwardenCipher.Card.number),
                    DiffFinderNode.Leaf(BitwardenCipher.Card.expMonth),
                    DiffFinderNode.Leaf(BitwardenCipher.Card.expYear),
                    DiffFinderNode.Leaf(BitwardenCipher.Card.code),
                ),
            ),
            DiffFinderNode.Group(
                lens = BitwardenCipher.identity,
                identity = {
                    BitwardenCipher.Identity(
                    )
                },
                children = listOf(
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.title),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.firstName),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.middleName),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.lastName),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.address1),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.address2),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.address3),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.city),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.state),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.postalCode),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.country),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.company),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.email),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.phone),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.ssn),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.username),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.passportNumber),
                    DiffFinderNode.Leaf(BitwardenCipher.Identity.licenseNumber),
                ),
            ),
            DiffFinderNode.Leaf(BitwardenCipher.sshKey),
        ),
    )
}

fun BitwardenCipher.Login.Uri.Companion.getUrlChecksum(
    cryptoGenerator: CryptoGenerator,
    uri: String?,
): ByteArray {
    return cryptoGenerator.hashSha256(uri.orEmpty().toByteArray())
}

fun BitwardenCipher.Login.Uri.Companion.getUrlChecksumBase64(
    cryptoGenerator: CryptoGenerator,
    base64Service: Base64Service,
    uri: String?,
): String {
    val rawHash = getUrlChecksum(
        cryptoGenerator = cryptoGenerator,
        uri = uri,
    )
    return base64Service.encodeToString(rawHash)
}

fun BitwardenCipher.Companion.generated(): BitwardenCipher {
    val accountIds = listOf(
        "account_a",
        "account_b",
        "account_c",
    )
    val folderIds = listOf(
        "folder_a",
        "folder_b",
        "folder_c",
        null,
    )
    val name = listOf(
        "**Lorem ipsum dolor sit amet**, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magnam aliquam quaerat voluptatem. Ut enim medicorum scientiam non ipsius artis, sed bonae valetudinis causa probamus, et gubernatoris ars, quia bene navigandi rationem habet, utilitate, non arte.\n" +
                "\n" +
                "Qui officia deserunt mollit anim id est voluptatem et dolorem. Ad haec et quae fugiamus refert omnia. Quod quamquam Aristippi est a Chrysippo praetermissum in Stoicis? Legimus tamen Diogenem, Antipatrum, Mnesarchum, Panaetium, multos alios in primisque familiarem nostrum Posidonium. Quid? Theophrastus mediocriterne delectat, cum tractat locos ab Aristotele ante tractatos? Quid? Epicurei num desistunt de isdem, de quibus ante dictum.\n" +
                "\n" +
                "Cum ita esset affecta, secundum non recte, si voluptas esset bonum, fuisse desideraturam. Idcirco enim non desideraret, quia, quod dolore caret, id in hominum consuetudine facilius fieri poterit et iustius?",
    )
    return BitwardenCipher(
        cipherId = Uuid.random().toString(),
        accountId = accountIds.random(),
        folderId = folderIds.random(),
        revisionDate = Clock.System.now().minus(20L.days),
        service = BitwardenService(),
        favorite = Math.random() > 0.5f,
        reprompt = BitwardenCipher.RepromptType.None,
        name = name.random(),
        notes = name.random(),
        type = BitwardenCipher.Type.Login,
        login = BitwardenCipher.Login(
            username = "test",
            password = "qwerty",
            uris = listOf(),
        ),
    )
}
