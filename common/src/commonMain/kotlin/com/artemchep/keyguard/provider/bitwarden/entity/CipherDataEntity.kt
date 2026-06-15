package com.artemchep.keyguard.provider.bitwarden.entity

import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class CipherDataEntity(
    @JsonNames("name")
    @SerialName("Name")
    val name: String? = null,
    @JsonNames("notes")
    @SerialName("Notes")
    val notes: String? = null,
    @JsonNames("fields")
    @SerialName("Fields")
    val fields: List<FieldEntity>? = null,
    @JsonNames("passwordHistory")
    @SerialName("PasswordHistory")
    val passwordHistory: List<PasswordHistoryEntity>? = null,

    // Login
    @JsonNames("uris")
    @SerialName("Uris")
    val uris: List<LoginUriEntity>? = null,
    @JsonNames("username")
    @SerialName("Username")
    val username: String? = null,
    @JsonNames("password")
    @SerialName("Password")
    val password: String? = null,
    @JsonNames("passwordRevisionDate")
    @SerialName("PasswordRevisionDate")
    val passwordRevisionDate: Instant? = null,
    @JsonNames("totp")
    @SerialName("Totp")
    val totp: String? = null,
    @JsonNames("fido2Credentials")
    @SerialName("Fido2Credentials")
    val fido2Credentials: List<LoginFido2CredentialsEntity>? = null,

    // Secure note
    @JsonNames("type")
    @SerialName("Type")
    val secureNoteType: SecureNoteTypeEntity? = null,

    // Card
    @JsonNames("cardholderName")
    @SerialName("CardholderName")
    val cardholderName: String? = null,
    @JsonNames("brand")
    @SerialName("Brand")
    val brand: String? = null,
    @JsonNames("number")
    @SerialName("Number")
    val number: String? = null,
    @JsonNames("expMonth")
    @SerialName("ExpMonth")
    val expMonth: String? = null,
    @JsonNames("expYear")
    @SerialName("ExpYear")
    val expYear: String? = null,
    @JsonNames("code")
    @SerialName("Code")
    val code: String? = null,

    // Identity
    @JsonNames("title")
    @SerialName("Title")
    val title: String? = null,
    @JsonNames("firstName")
    @SerialName("FirstName")
    val firstName: String? = null,
    @JsonNames("middleName")
    @SerialName("MiddleName")
    val middleName: String? = null,
    @JsonNames("lastName")
    @SerialName("LastName")
    val lastName: String? = null,
    @JsonNames("address1")
    @SerialName("Address1")
    val address1: String? = null,
    @JsonNames("address2")
    @SerialName("Address2")
    val address2: String? = null,
    @JsonNames("address3")
    @SerialName("Address3")
    val address3: String? = null,
    @JsonNames("city")
    @SerialName("City")
    val city: String? = null,
    @JsonNames("state")
    @SerialName("State")
    val state: String? = null,
    @JsonNames("postalCode")
    @SerialName("PostalCode")
    val postalCode: String? = null,
    @JsonNames("country")
    @SerialName("Country")
    val country: String? = null,
    @JsonNames("company")
    @SerialName("Company")
    val company: String? = null,
    @JsonNames("email")
    @SerialName("Email")
    val email: String? = null,
    @JsonNames("phone")
    @SerialName("Phone")
    val phone: String? = null,
    @JsonNames("ssn")
    @SerialName("SSN")
    val ssn: String? = null,
    @JsonNames("passportNumber")
    @SerialName("PassportNumber")
    val passportNumber: String? = null,
    @JsonNames("licenseNumber")
    @SerialName("LicenseNumber")
    val licenseNumber: String? = null,

    // SSH key
    @JsonNames("privateKey")
    @SerialName("PrivateKey")
    val privateKey: String? = null,
    @JsonNames("publicKey")
    @SerialName("PublicKey")
    val publicKey: String? = null,
    @JsonNames("keyFingerprint")
    @SerialName("KeyFingerprint")
    val keyFingerprint: String? = null,
)

// In Vaultwarden it seems like we are using the nested JSON object:
// https://github.com/dani-garcia/vaultwarden/issues/6988
//
// however the official server seem to output it as a string instead.
object CipherDataEntitySerializer : KSerializer<CipherDataEntity?> {
    private val fallbackJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override val descriptor: SerialDescriptor =
        CipherDataEntity.serializer().nullable.descriptor

    override fun deserialize(decoder: Decoder): CipherDataEntity? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return runCatching {
                decoder.decodeSerializableValue(CipherDataEntity.serializer())
            }.getOrNull()
        return jsonDecoder.decodeJsonElement().decodeOrNull(jsonDecoder.json)
    }

    override fun serialize(encoder: Encoder, value: CipherDataEntity?) {
        if (value == null) {
            encoder.encodeNull()
            return
        }

        val json = (encoder as? JsonEncoder)?.json ?: fallbackJson
        val data = json.encodeToString(
            serializer = CipherDataEntity.serializer(),
            value = value,
        )
        encoder.encodeString(data)
    }

    private fun JsonElement.decodeOrNull(json: Json): CipherDataEntity? =
        when (this) {
            JsonNull -> null
            is JsonObject -> decodeObjectOrNull(json, this)
            is JsonPrimitive -> decodePrimitiveOrNull(json, this)
            else -> null
        }

    private fun decodePrimitiveOrNull(
        json: Json,
        primitive: JsonPrimitive,
    ): CipherDataEntity? {
        if (!primitive.isString) {
            return null
        }
        val data = primitive.content
            .takeIf { it.isNotBlank() }
            ?: return null
        return runCatching {
            json.decodeFromString(
                deserializer = CipherDataEntity.serializer(),
                string = data,
            )
        }.getOrNull()
    }

    private fun decodeObjectOrNull(
        json: Json,
        obj: JsonObject,
    ): CipherDataEntity? =
        runCatching {
            json.decodeFromJsonElement(
                deserializer = CipherDataEntity.serializer(),
                element = obj,
            )
        }.getOrNull()
}
