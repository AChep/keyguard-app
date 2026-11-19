package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.service.session.VaultSessionLocker
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnRpIdValidator
import com.artemchep.keyguard.util.webauthn.crypto.NativePasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import kotlin.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A vault window of a passkey request of an extension, where the user picks
 * the item: a passkey to sign in with, or the login that stores a new one.
 * The pick approves the request, so the window names who asks.
 */
class BrowserPasskeyPicker(
    val id: String,
    /** The session that asks. */
    val session: BrowserExtensionSession,
    /** The name of the session's pairing, for display. */
    val pairingName: String?,
    /** The origin of the page that asks, such as `https://login.example.com`, for display. */
    val origin: String,
    /** The relying party, for display. */
    val rpId: String,
    /** `true` to save a new passkey, `false` to sign in with one. */
    val create: Boolean,
    val expiresAt: Instant,
    /** The vault in the picking mode of the request; picking an item answers it. */
    val mode: AppMode,
    /** Hands the request back to the browser, which offers its own passkeys and devices. */
    val onAnotherDevice: () -> Unit,
    /** The user closed the window. */
    val onDismiss: () -> Unit,
)

/** The vault windows of the passkey requests of extensions; a platform shows each one. */
interface BrowserPasskeyPickers {
    val passkeyPickers: StateFlow<List<BrowserPasskeyPicker>>
}

/**
 * What the passkey requests of the extensions need beyond the vault: the
 * checks of the relying party, the authenticator, and the windows where the
 * user picks an item. App-scoped, shared by every session.
 */
class BrowserPasskeys(
    /** Checks a relying party against a page; may read its related origins. */
    private val rpIdValidator: WebAuthnRpIdValidator,
    internal val authenticator: WebAuthnAuthenticator,
    /** Keeps the vault unlocked while a request is served. */
    internal val vaultSessionLocker: VaultSessionLocker,
    /** The algorithms of the passkeys that the authenticator creates. */
    internal val supportedAlgorithms: Set<PasskeySignatureAlgorithm> = NativePasskeyCrypto.supportedAlgorithms,
) : BrowserPasskeyPickers {
    private val mutablePickers = MutableStateFlow<List<BrowserPasskeyPicker>>(emptyList())
    override val passkeyPickers = mutablePickers.asStateFlow()

    /**
     * The canonical relying party ID of [rpId], or of the host of [origin]
     * without one, if the page at [origin] may use it; otherwise `null`.
     */
    internal suspend fun validateRpId(rpId: String?, origin: String): String? =
        runCatchingUnlessCancelled { rpIdValidator.resolveAndValidateRpId(rpId, origin) }
            .getOrNull()

    /** Shows the window of [picker]; `false` while its session has one open. */
    internal fun open(picker: BrowserPasskeyPicker): Boolean = mutablePickers.addOnePerSession(picker) { it.session }

    internal fun close(id: String) {
        mutablePickers.update { list -> list.filterNot { it.id == id } }
    }
}
