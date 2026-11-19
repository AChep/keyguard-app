package com.artemchep.keyguard.feature.browserextension.protocol

/** The constants of `browserExtension/protocol/README.md`, version 1. */
object BrowserProtocol {
    /** The protocol version of this document. */
    const val VERSION: Int = 1

    /** The suite ID, the first frame of a session: `Noise_XX_25519_ChaChaPoly_SHA256`. */
    const val SUITE_ID: Byte = 3

    /** The largest frame of the stream: one Noise message. */
    const val MAX_FRAME_SIZE: Int = 65_535

    /** How long the handshake of a session may take. */
    const val HANDSHAKE_TIMEOUT_MS: Long = 20_000L

    /** The most requests of one session in flight; the app answers more with `busy`. */
    const val MAX_IN_FLIGHT: Int = 8

    /** The most sessions the app serves at once. */
    const val MAX_SESSIONS: Int = 8

    /**
     * The most `pair_commit`s of all sessions whose prompt the user hasn't
     * seen, within [UNPROMPTED_COMMIT_WINDOW_MS]; the app answers more with `busy`.
     */
    const val MAX_UNPROMPTED_COMMITS: Int = 3

    /** How long a `pair_commit` without a prompt counts toward [MAX_UNPROMPTED_COMMITS]. */
    const val UNPROMPTED_COMMIT_WINDOW_MS: Long = 3_600_000L

    /** The size of a pairing nonce and of its commitment, in bytes. */
    const val NONCE_SIZE: Int = 32

    /** The longest name an extension suggests for itself, in characters. */
    const val MAX_NAME_LENGTH: Int = 64

    /** The largest request ID: 2^53 − 1, the largest integer JavaScript holds exactly. */
    const val MAX_ID: Long = (1L shl 53) - 1

    /** How deep the arrays and objects of a message nest at most. */
    const val MAX_DEPTH: Int = 64

    private const val PROLOGUE_PREFIX = "keyguard-browser/v1\n"

    /** The prologue of a session of the extension of [origin]. */
    fun prologue(origin: String): ByteArray = (PROLOGUE_PREFIX + origin).encodeToByteArray()
}

/** The origins the browsers pass to the connector, as the agent validates them. */
object BrowserOrigin {
    private const val CHROME_PREFIX = "chrome-extension://"
    private const val CHROME_ID_LENGTH = 32
    private const val MAX_FIREFOX_ID_LENGTH = 255
    private const val FIRST_PRINTABLE = '!'
    private const val LAST_PRINTABLE = '~'

    /**
     * `chrome-extension://` with 32 letters `a` to `p`, then `/`; or the ID
     * of a Firefox extension: 1 to 255 printable ASCII characters without
     * spaces or `/`. A string that starts as a Chromium origin is only ever
     * read as one.
     */
    fun isValid(origin: String): Boolean {
        if (origin.startsWith(CHROME_PREFIX)) {
            val id = origin.removePrefix(CHROME_PREFIX).removeSuffix("/")
            return origin.endsWith("/") &&
                id.length == CHROME_ID_LENGTH &&
                id.all { it in 'a'..'p' }
        }
        return origin.length in 1..MAX_FIREFOX_ID_LENGTH &&
            origin.all { it in FIRST_PRINTABLE..LAST_PRINTABLE && it != '/' }
    }
}

/** The error codes of a response, README section 9. */
enum class BrowserError(val code: String) {
    VAULT_LOCKED("vault_locked"),
    UNPAIRED("unpaired"),
    UPDATE_REQUIRED("update_required"),
    USER_DENIED("user_denied"),
    APPROVAL_TIMEOUT("approval_timeout"),
    NOT_ALLOWED("not_allowed"),
    SECURITY("security"),
    NOT_SUPPORTED("not_supported"),
    INVALID_REQUEST("invalid_request"),

    /** A passkey that `excludeCredentials` names is already in the vault: the page gets `InvalidStateError`. */
    INVALID_STATE("invalid_state"),
    TOO_LARGE("too_large"),
    BUSY("busy"),

    /** The extension cancelled the request with `cancel`. */
    CANCELLED("cancelled"),
}

/** A request that the app answers with an error [code]. */
class BrowserRequestException(val code: BrowserError) : Exception(code.code)

/** A message that breaks the protocol; the app ends the session. Its text never holds the message. */
class BrowserProtocolException(reason: String) : Exception(reason)

/** The optional features of an extension, README section 5. */
enum class BrowserCapability(val wireName: String) {
    PASSKEYS("passkeys"),
    SAVE("save"),
    ;

    companion object {
        fun of(wireName: String): BrowserCapability? = entries.firstOrNull { it.wireName == wireName }
    }
}

/** What a session must be before the app serves a request, README section 4. */
enum class BrowserGate {
    /** Any session. */
    NONE,

    /** An unpaired session. */
    UNPAIRED,

    /** A paired session. */
    PAIRED,

    /** A paired session, while the vault is unlocked. */
    UNLOCKED,

    /** Unlocked, and the user approves the release. */
    APPROVAL,
    ;

    /** The error that refuses a request of this gate, or `null` when the session passes it. */
    fun check(paired: Boolean, unlocked: Boolean): BrowserError? = when (this) {
        NONE -> null
        UNPAIRED -> BrowserError.INVALID_REQUEST.takeIf { paired }
        PAIRED -> BrowserError.UNPAIRED.takeUnless { paired }
        UNLOCKED, APPROVAL -> when {
            !paired -> BrowserError.UNPAIRED
            !unlocked -> BrowserError.VAULT_LOCKED
            else -> null
        }
    }
}

/** The requests of version 1, README section 5. */
enum class BrowserRequestType(
    val wireName: String,
    val gate: BrowserGate,
    /** The capability the session must have agreed on, if any. */
    val capability: BrowserCapability? = null,
) {
    HELLO("hello", BrowserGate.NONE),

    /** Answered at once, and never in flight itself. */
    CANCEL("cancel", BrowserGate.NONE),
    PAIR_COMMIT("pair_commit", BrowserGate.UNPAIRED),
    PAIR_REVEAL("pair_reveal", BrowserGate.UNPAIRED),
    UNLOCK("unlock", BrowserGate.PAIRED),
    SUGGEST("suggest", BrowserGate.UNLOCKED),
    SEARCH("search", BrowserGate.UNLOCKED),
    FILL("fill", BrowserGate.APPROVAL),
    TOTP("totp", BrowserGate.APPROVAL),
    GENERATE("generate", BrowserGate.PAIRED),
    SAVE("save", BrowserGate.UNLOCKED, BrowserCapability.SAVE),

    /** Adds a blocked URI that stops saving on the site of the page. */
    NEVER_SAVE("never_save", BrowserGate.UNLOCKED, BrowserCapability.SAVE),
    // Paired: a passkey request shows the unlock prompt while the vault is
    // locked, then asks the user as the approval gate does.
    PASSKEY_GET("passkey_get", BrowserGate.PAIRED, BrowserCapability.PASSKEYS),
    PASSKEY_CREATE("passkey_create", BrowserGate.PAIRED, BrowserCapability.PASSKEYS),
    ;

    companion object {
        /** The request of [wireName], or `null` for one this version does not know. */
        fun of(wireName: String): BrowserRequestType? = entries.firstOrNull { it.wireName == wireName }
    }
}
