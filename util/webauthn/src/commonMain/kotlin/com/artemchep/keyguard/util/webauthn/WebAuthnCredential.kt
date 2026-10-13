package com.artemchep.keyguard.util.webauthn

/** A credential source, independent of vault storage and provider APIs. */
data class WebAuthnCredential(
    val credentialId: String,
    val keyType: String,
    val keyAlgorithm: String,
    val keyCurve: String,
    val keyValue: String,
    val rpId: String,
    val discoverable: Boolean,
    val rpName: String? = null,
    val counter: Int? = null,
    val userHandle: String? = null,
    val userName: String? = null,
    val userDisplayName: String? = null,
) {
    override fun toString(): String = "WebAuthnCredential(credentialId=$credentialId, rpId=$rpId, keyValue=<redacted>)"
}

/**
 * The bytes the relying party assigned as the user handle. Any Base64 form of the stored
 * value carries the same bytes; an unreadable value is never sent, since a wrong handle
 * is worse than none.
 */
fun WebAuthnCredential.userHandleBytes(): ByteArray? = userHandle?.let(PasskeyBase64::decodeUserHandleOrNull)

/**
 * Whether the credential may answer a request without `allowCredentials`. Only a
 * discoverable credential may, and its user handle is how the relying party identifies
 * the account; without a readable one, the assertion would fail after the user verified.
 *
 * Spec: https://www.w3.org/TR/webauthn-3/#sctn-op-get-assertion
 */
fun WebAuthnCredential.isUsableWithoutAllowList(): Boolean = discoverable && userHandleBytes() != null

/** A stored credential's identifiers in wire form, each parsed once. */
class WebAuthnCredentialWire(
    val credentialId: ByteArray,
    /** `null` when the stored user handle is unreadable, see [userHandleBytes]. */
    val userHandle: ByteArray?,
)

/**
 * The wire identifiers of the credential if it can answer a get-assertion request for
 * [rpId] that allows the [allowed] credentials, else `null`. These are the authenticator's
 * checks before it reads the private key, so a provider never offers a passkey that it then
 * cannot sign with, and a listing parses each identifier once.
 *
 * Spec: https://www.w3.org/TR/webauthn-3/#sctn-op-get-assertion
 */
fun WebAuthnCredential.wireIfCanAnswer(
    rpId: String,
    allowed: WebAuthnAllowedCredentialDescriptors,
): WebAuthnCredentialWire? = PasskeyCredentialId
    .takeIf { this.rpId == rpId && isSignableStoredKey() }
    ?.encodeOrNull(credentialId)
    ?.let { WebAuthnCredentialWire(credentialId = it, userHandle = userHandleBytes()) }
    ?.takeIf { allowed.allows(this, it) }

/** Whether the credential can answer the request, see [wireIfCanAnswer]. */
fun WebAuthnCredential.canAnswer(
    rpId: String,
    allowed: WebAuthnAllowedCredentialDescriptors,
): Boolean = wireIfCanAnswer(rpId, allowed) != null

/**
 * The wire identifiers of the credential if a system index of passkeys may list it before
 * any request names it, else `null`: it must be able to answer every request for its
 * relying party.
 */
fun WebAuthnCredential.wireIfIndexable(): WebAuthnCredentialWire? =
    if (rpId.isBlank()) null else wireIfCanAnswer(rpId, WebAuthnAllowedCredentialDescriptors.NONE)

/** Whether a system index of passkeys may list the credential, see [wireIfIndexable]. */
fun WebAuthnCredential.isIndexable(): Boolean = wireIfIndexable() != null

/**
 * Caller information established by the provider adapter. [origin] must come
 * from a trusted caller, and [rpId] must be resolved and validated against it
 * before using this context to create or assert a credential.
 */
data class WebAuthnCallerContext(
    val origin: String,
    val rpId: String,
    val androidPackageName: String? = null,
)

data class WebAuthnRegistrationResult(
    val responseJson: String,
    val credential: WebAuthnCredential,
)
