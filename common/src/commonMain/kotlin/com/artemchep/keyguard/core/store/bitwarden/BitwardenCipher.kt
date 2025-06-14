package com.artemchep.keyguard.core.store.bitwarden

import arrow.optics.Getter
import arrow.optics.optics
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil.DiffApplierByListValue
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil.DiffFinderNode
import com.artemchep.keyguard.common.service.text.Base64Service
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
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
    val fields: List<Field> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
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

    @Serializable
    enum class Type {
        Login,
        SecureNote,
        Card,
        Identity,
        SshKey,
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
