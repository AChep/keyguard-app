package com.artemchep.keyguard.common.service.passkey.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthenticatorAssertionClientData(
    val type: String, // webauthn.get
    // base64
    val challenge: String,
    val origin: String,
    /**
     * Android app package name, if the app
     * does exist.
     */
    val androidPackageName: String? = null,
)

@Serializable
data class AuthenticatorAssertion(
    // base64
    val clientDataJSON: String,
    // base64
    val authenticatorData: String,
    val signature: String,
    val userHandle: String,
    val publicKeyAlgorithm: Int,
    val publicKey: String,
)

// See:
// https://webauthn.guide/

@Serializable
enum class CreatePasskeyAttestation {
    /**
     * Means that the server wishes to receive
     * the attestation data from the authenticator.
     */
    @SerialName("direct")
    DIRECT,

    @SerialName("enterprise")
    ENTERPRISE,

    /**
     * Means that the server will allow for anonymized attestation data.
     */
    @SerialName("indirect")
    INDIRECT,

    /**
     * Indicates that the server does not care about attestation.
     */
    @SerialName("none")
    NONE,
}

@Serializable
data class CreatePasskeyRelyingParty(
    val id: String? = null,
    val name: String,
)

@Serializable
data class CreatePasskeyUser(
    // base64
    val id: String,
    val name: String,
    val displayName: String,
)

@Serializable
data class CreatePasskeyPubKeyCredParams(
    // https://www.iana.org/assignments/cose/cose.xhtml#algorithms
    val alg: Double,
    val type: String,
)

// https://www.w3.org/TR/webauthn-2/#dictionary-authenticatorSelection
@Serializable
data class CreatePasskeyAuthenticatorSelection(
    val residentKey: String = "discouraged",
    val requireResidentKey: Boolean = residentKey == "required",
    val userVerification: String? = null,
)

/*
 * {
 * "attestation":"none",
 * "authenticatorSelection":{
 *    "authenticatorAttachment":"platform",
 *    "requireResidentKey":false,
 *    "residentKey":"required",
 *    "userVerification":"required"
 *  },
 *  "challenge":"t2cLubF-1PFSjgNAkyZwqC_aooWQUyBcjEDSj56wJKA",
 *   "excludeCredentials":[],
 *   "pubKeyCredParams":[
 *     {"alg":-7,"type":"public-key"}
 *   ],
 *   "rp":{
 *     "id":"dashlanepasskeydemo.com",
 *     "name":"Dashlane Passkey Demo"
 *   },
 *   "timeout":1800000,
 *   "user":{
 *     "displayName":"artemchep@gmail.com",
 *     "id":"ab4ad29c-e435-45cb-995b-9a36600e0f85",
 *     "name":"artemchep@gmail.com"
 *   }
 *  }
 */
@Serializable
data class CreatePasskey(
    // TODO: Make it fall back to none
    val attestation: CreatePasskeyAttestation? = CreatePasskeyAttestation.NONE,
    val authenticatorSelection: CreatePasskeyAuthenticatorSelection = CreatePasskeyAuthenticatorSelection(),
    /**
     * The challenge is a buffer of cryptographically random bytes generated
     * on the server, and is needed to prevent "replay attacks".
     */
    // base64
    val challenge: String,
    val pubKeyCredParams: List<CreatePasskeyPubKeyCredParams>,
    /**
     * Describing the organization responsible
     * for registering and authenticating the user.
     */
    val rp: CreatePasskeyRelyingParty,
    /**
     * This is information about the user currently registering.
     * The authenticator uses the id to associate a credential with the user.
     */
    val user: CreatePasskeyUser,
    /**
     * The time (in milliseconds) that the user has to respond
     * to a prompt for registration before an error is returned.
     */
    val timeout: Double? = null,
)
