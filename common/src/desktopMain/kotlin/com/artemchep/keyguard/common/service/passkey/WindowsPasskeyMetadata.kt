package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnPublicKeyCredentialDescriptor
import com.artemchep.keyguard.util.webauthn.isIndexable
import com.artemchep.keyguard.util.webauthn.userHandleBytes
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyIdentity
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyLimits
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyRequest
import java.util.Arrays
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Matches the watchtower's vault debounce. A new passkey reaches Windows about a second later. */
private val INDEX_DEBOUNCE = 1.seconds

internal fun WindowsPasskeyRequest.allowed() = WebAuthnAllowedCredentialDescriptors(
    isAllowCredentialsSupplied = credentials.isNotEmpty(),
    descriptors = credentials.map {
        WebAuthnPublicKeyCredentialDescriptor(it.type, PasskeyCredentialId.decode(it.id))
    },
)

internal fun DSecret.Login.Fido2Credentials.windowsUserName(cipher: DSecret): String? =
    sequenceOf(userName, userDisplayName, cipher.login?.username, cipher.name)
        .filterNotNull().firstOrNull { it.isNotBlank() }

/** First valid duplicate wins. Sort by ID so unique entries ignore vault ordering. */
internal fun windowsPasskeyIdentities(ciphers: List<DSecret>): List<WindowsPasskeyIdentity> = ciphers
    .availablePasskeys()
    .mapNotNull { (cipher, credential) -> credential.windowsIdentity(cipher) }
    .distinctBy { it.credentialId.asList() }
    .sortedWith { a, b -> Arrays.compareUnsigned(a.credentialId, b.credentialId) }

/**
 * The public index Windows should hold. Vault bursts (sync, import) settle first. A native sync
 * cannot be interrupted, so a slow one never holds back the shared vault flow, and only the
 * newest index waits behind it.
 */
@OptIn(FlowPreview::class)
internal fun Flow<List<DSecret>>.windowsPasskeyIndex(): Flow<List<WindowsPasskeyIdentity>> = this
    .debounce(INDEX_DEBOUNCE)
    .map(::windowsPasskeyIdentities)
    .distinctUntilChanged()
    .conflate()

private fun DSecret.Login.Fido2Credentials.windowsIdentity(cipher: DSecret): WindowsPasskeyIdentity? {
    val credential = toWebAuthnCredential()
    val id = PasskeyCredentialId.encodeOrNull(credentialId)
    val handle = credential.userHandleBytes()
    val username = windowsUserName(cipher)
    return when {
        // An indexable credential has an encodable ID and a readable user handle.
        !credential.isIndexable() || id == null || handle == null -> null
        !WindowsPasskeyLimits.acceptsCredentialId(id) -> null
        !WindowsPasskeyLimits.acceptsUserId(handle) -> null
        username == null -> null
        !listOfNotNull(rpId, rpName, username, userDisplayName).all(WindowsPasskeyLimits::acceptsText) -> null
        else -> WindowsPasskeyIdentity(
            credentialId = id,
            rpId = rpId,
            rpName = rpName?.takeIf(String::isNotBlank) ?: rpId,
            userId = handle,
            userName = username,
            userDisplayName = userDisplayName?.takeIf(String::isNotBlank) ?: username,
        )
    }
}
