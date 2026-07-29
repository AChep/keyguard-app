package com.artemchep.keyguard.common.service.credentialexchange.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * A single credential carried by a CXF [item][CxfItem]. The concrete kind is
 * encoded through the `type` JSON discriminator.
 *
 * Only the subtypes Keyguard can map to its vault model are modeled; the CXF
 * specification defines further kinds (api-key, wifi, file, ...) which the
 * import path counts as skipped.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface CxfCredential {
    /**
     * A WebAuthn/FIDO2 passkey. All binary-carrying fields are base64url
     * strings; [key] is a PKCS#8 DER private key.
     */
    @Serializable
    @SerialName("passkey")
    data class Passkey(
        val credentialId: String,
        val rpId: String,
        val username: String,
        val userDisplayName: String,
        val userHandle: String,
        val key: String,
    ) : CxfCredential

    /**
     * A basic username/password credential.
     */
    @Serializable
    @SerialName("basic-auth")
    data class BasicAuth(
        val username: CxfEditableField? = null,
        val password: CxfEditableField? = null,
    ) : CxfCredential

    @Serializable
    @SerialName("credit-card")
    data class CreditCard(
        val number: CxfEditableField? = null,
        val fullName: CxfEditableField? = null,
        val cardType: CxfEditableField? = null,
        val verificationNumber: CxfEditableField? = null,
        val pin: CxfEditableField? = null,
        val expiryDate: CxfEditableField? = null,
        val validFrom: CxfEditableField? = null,
    ) : CxfCredential

    @Serializable
    @SerialName("address")
    data class Address(
        val streetAddress: CxfEditableField? = null,
        val postalCode: CxfEditableField? = null,
        val city: CxfEditableField? = null,
        val territory: CxfEditableField? = null,
        val country: CxfEditableField? = null,
        val tel: CxfEditableField? = null,
    ) : CxfCredential

    /**
     * A person's name, broken into its constituent parts.
     */
    @Serializable
    @SerialName("person-name")
    data class PersonName(
        val title: CxfEditableField? = null,
        val given: CxfEditableField? = null,
        val givenInformal: CxfEditableField? = null,
        val given2: CxfEditableField? = null,
        val surnamePrefix: CxfEditableField? = null,
        val surname: CxfEditableField? = null,
        val surname2: CxfEditableField? = null,
        val credentials: CxfEditableField? = null,
        val generation: CxfEditableField? = null,
    ) : CxfCredential

    @Serializable
    @SerialName("passport")
    data class Passport(
        val issuingCountry: CxfEditableField? = null,
        val nationality: CxfEditableField? = null,
        val fullName: CxfEditableField? = null,
        val birthDate: CxfEditableField? = null,
        val birthPlace: CxfEditableField? = null,
        val sex: CxfEditableField? = null,
        val issueDate: CxfEditableField? = null,
        val expiryDate: CxfEditableField? = null,
        val issuingAuthority: CxfEditableField? = null,
        val passportType: CxfEditableField? = null,
        val passportNumber: CxfEditableField? = null,
        val nationalIdentificationNumber: CxfEditableField? = null,
    ) : CxfCredential

    @Serializable
    @SerialName("drivers-license")
    data class DriversLicense(
        val fullName: CxfEditableField? = null,
        val birthDate: CxfEditableField? = null,
        val issueDate: CxfEditableField? = null,
        val expiryDate: CxfEditableField? = null,
        val issuingAuthority: CxfEditableField? = null,
        val territory: CxfEditableField? = null,
        val country: CxfEditableField? = null,
        val licenseNumber: CxfEditableField? = null,
        val licenseClass: CxfEditableField? = null,
    ) : CxfCredential

    @Serializable
    @SerialName("identity-document")
    data class IdentityDocument(
        val issuingCountry: CxfEditableField? = null,
        val documentNumber: CxfEditableField? = null,
        val identificationNumber: CxfEditableField? = null,
        val nationality: CxfEditableField? = null,
        val fullName: CxfEditableField? = null,
        val birthDate: CxfEditableField? = null,
        val birthPlace: CxfEditableField? = null,
        val sex: CxfEditableField? = null,
        val issueDate: CxfEditableField? = null,
        val expiryDate: CxfEditableField? = null,
        val issuingAuthority: CxfEditableField? = null,
    ) : CxfCredential

    /**
     * A free-form secure note.
     */
    @Serializable
    @SerialName("note")
    data class Note(
        val content: CxfEditableField,
    ) : CxfCredential

    /**
     * An SSH private key.
     */
    @Serializable
    @SerialName("ssh-key")
    data class SshKey(
        /**
         * The OpenSSH key-type token,
         * e.g. `ssh-ed25519` or `ssh-rsa`.
         */
        val keyType: String,
        /**
         * The PKCS#8 ASN.1 DER private key, base64url-encoded.
         */
        val privateKey: String,
        val keyComment: String? = null,
        val creationDate: CxfEditableField? = null,
        val expiryDate: CxfEditableField? = null,
        /**
         * Where the key was originally generated, e.g. a URL. CXF v1.0
         * §3.3.15 types this as an `EditableField<"string">` — a JSON object,
         * not a bare string.
         */
        val keyGenerationSource: CxfEditableField? = null,
    ) : CxfCredential

    @Serializable
    @SerialName("custom-fields")
    data class CustomFields(
        val fields: List<CxfEditableField>,
        val id: String? = null,
        val label: String? = null,
    ) : CxfCredential

    /**
     * A time-based one-time password (TOTP) generator. §3.3.16 types
     * `algorithm` as `OTPHashAlgorithm / tstr`, so besides the three hash
     * members it also carries the industry `steam` extension value
     * ([Totp.ALGORITHM_STEAM]); mOTP and HOTP have no representation.
     */
    @Serializable
    @SerialName("totp")
    data class Totp(
        /**
         * The shared secret, base32-encoded.
         */
        val secret: String,
        val period: Int,
        val digits: Int,
        /**
         * The hash algorithm, one of [ALGORITHM_SHA1], [ALGORITHM_SHA256],
         * [ALGORITHM_SHA512] or the [ALGORITHM_STEAM] extension. Decode it
         * through `CxfTotpAlgorithm.fromWireOrNull`, never by string
         * comparison.
         */
        val algorithm: String,
        val username: String? = null,
        val issuer: String? = null,
    ) : CxfCredential {
        companion object {
            const val ALGORITHM_SHA1 = "sha1"
            const val ALGORITHM_SHA256 = "sha256"
            const val ALGORITHM_SHA512 = "sha512"

            /**
             * Not a member of `OTPHashAlgorithm`; legal only through the
             * `/ tstr` arm of §3.3.16's union. Pinned deviation D8.
             */
            const val ALGORITHM_STEAM = "steam"
        }
    }
}
