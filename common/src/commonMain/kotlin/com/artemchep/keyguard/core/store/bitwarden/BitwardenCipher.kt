package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.optics
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.time.Duration.Companion.days

@Serializable
@optics
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
    // service fields
    override val service: BitwardenService,
    // common
    val keyBase64: String? = null,
    val name: String?,
    val notes: String?,
    val favorite: Boolean,
    val ignoredAlerts: Map<IgnoreAlertType, IgnoreAlertData> = emptyMap(),
    val fields: List<Field> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val reprompt: RepromptType,
    // types
    val type: Type,
    val login: Login? = null,
    val secureNote: SecureNote? = null,
    val card: Card? = null,
    val identity: Identity? = null,
) : BitwardenService.Has<BitwardenCipher> {
    companion object;

    override fun withService(service: BitwardenService) = copy(service = service)

    @Serializable
    enum class Type {
        Login,
        SecureNote,
        Card,
        Identity,
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
            val keyBase64: String,
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

        @Serializable
        data class Uri(
            val uri: String? = null,
            val match: MatchType? = null,
        ) {
            @Serializable
            enum class MatchType {
                Domain,
                Host,
                StartsWith,
                Exact,
                RegularExpression,
                Never,
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
        cipherId = UUID.randomUUID().toString(),
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
