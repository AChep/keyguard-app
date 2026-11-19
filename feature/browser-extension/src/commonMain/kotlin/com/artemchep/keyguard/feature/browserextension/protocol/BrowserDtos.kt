package com.artemchep.keyguard.feature.browserextension.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The fields of each message of README section 5 and 6. Byte strings are
// standard Base64 with padding; WebAuthn JSON keeps its own base64url.

/** The state of the vault, as a response or an event reports it. */
@Serializable
enum class BrowserVaultState {
    @SerialName("locked")
    LOCKED,

    @SerialName("unlocked")
    UNLOCKED,
}

@Serializable
data class HelloRequest(
    /** The highest protocol version the extension speaks. */
    val protocol: Int,
    val capabilities: List<String>,
)

@Serializable
data class HelloResponse(
    /** The version of the session: the lower of both sides' highest. */
    val protocol: Int,
    val capabilities: List<String>,
    val paired: Boolean,
    val vault: BrowserVaultState,
)

@Serializable
data class PairCommitRequest(
    /** Base64 of the 32-byte commitment to the extension's nonce. */
    val commit: String,
    /** The name the extension suggests for itself, 1 to 64 characters. */
    val name: String,
)

@Serializable
data class PairCommitResponse(
    /** Base64 of the app's 32-byte nonce. */
    val nonce: String,
)

@Serializable
data class PairRevealRequest(
    /** Base64 of the extension's 32-byte nonce. */
    val nonce: String,
)

/** The response to `pair_reveal` and `unlock`. */
@Serializable
data class VaultResponse(
    val vault: BrowserVaultState,
)

@Serializable
data class SuggestRequest(
    val url: String,
    /** `"login"`, `"passkey"`; `null` means `["login"]`. */
    val kinds: List<String>? = null,
    @SerialName("rp_id")
    val rpId: String? = null,
)

@Serializable
data class SuggestResponse(
    /** A blocked URI stops autofill on the page; the items are empty. */
    val blocked: Boolean,
    /** A blocked URI stops saving a login on the page: true whenever [blocked] is. */
    @SerialName("save_blocked")
    val saveBlocked: Boolean,
    val truncated: Boolean,
    val items: List<SuggestItem>,
)

@Serializable
data class SuggestItem(
    val handle: String,
    val name: String,
    val username: String? = null,
    val account: String,
    @SerialName("has_password")
    val hasPassword: Boolean,
    @SerialName("has_totp")
    val hasTotp: Boolean,
    val reprompt: Boolean,
    val passkeys: List<SuggestPasskey>? = null,
    /** `true` on the first item when it is the login used last on this device; absent otherwise. */
    @SerialName("last_used")
    val lastUsed: Boolean? = null,
    /** `true` when the item's collections hide its password; absent otherwise. A fill still releases it. */
    @SerialName("password_hidden")
    val passwordHidden: Boolean? = null,
)

@Serializable
data class SuggestPasskey(
    val handle: String,
    @SerialName("user_name")
    val userName: String,
    @SerialName("user_display_name")
    val userDisplayName: String? = null,
)

@Serializable
data class SearchRequest(
    /** 2 to 100 code points, once trimmed. */
    val query: String,
    /** The page, as for `suggest`. */
    val url: String,
)

@Serializable
data class SearchResponse(
    val blocked: Boolean,
    val truncated: Boolean,
    val items: List<SearchItem>,
)

/** A [SuggestItem] without passkeys or `last_used`, and whether it matches the page. */
@Serializable
data class SearchItem(
    val handle: String,
    val name: String,
    val username: String? = null,
    val account: String,
    @SerialName("has_password")
    val hasPassword: Boolean,
    @SerialName("has_totp")
    val hasTotp: Boolean,
    val reprompt: Boolean,
    /** A URI of the item matches the page; a release of an item that doesn't always prompts. */
    val matched: Boolean,
    /** As for [SuggestItem.passwordHidden]. */
    @SerialName("password_hidden")
    val passwordHidden: Boolean? = null,
)

/** The request of `fill` and of `totp`. */
@Serializable
data class HandleRequest(
    val handle: String,
    val url: String,
    /** The origin of the tab's top frame, when the frame of [url] has another origin. */
    @SerialName("top_origin")
    val topOrigin: String? = null,
)

@Serializable
data class FillResponse(
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class TotpResponse(
    val code: String,
)

@Serializable
data class GenerateRequest(
    val url: String? = null,
)

@Serializable
data class GenerateResponse(
    val password: String,
)

@Serializable
data class SaveRequest(
    val url: String,
    val username: String? = null,
    val password: String? = null,
    @SerialName("previous_password")
    val previousPassword: String? = null,
)

@Serializable
data class NeverSaveRequest(
    /** The page, as for `save`. */
    val url: String,
)

@Serializable
data class PasskeyGetRequest(
    /** The page's origin, which the background takes from the sender. */
    val origin: String,
    /** `PublicKeyCredentialRequestOptionsJSON`. */
    val options: JsonObject,
    val mediation: String,
    @SerialName("same_origin_with_ancestors")
    val sameOriginWithAncestors: Boolean,
    val handle: String? = null,
)

@Serializable
data class PasskeyCreateRequest(
    val origin: String,
    /** `PublicKeyCredentialCreationOptionsJSON`. */
    val options: JsonObject,
    @SerialName("same_origin_with_ancestors")
    val sameOriginWithAncestors: Boolean,
)

/** The response of `passkey_get` and `passkey_create`. */
@Serializable
data class PasskeyResponse(
    /** `AuthenticationResponseJSON` or `RegistrationResponseJSON`. */
    val credential: JsonObject,
)

@Serializable
data class CancelRequest(
    /** The `id` of the request to cancel. */
    val target: Long,
)

/** The fields of the `vault` event. */
@Serializable
data class VaultEvent(
    val state: BrowserVaultState,
)
