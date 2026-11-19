package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserReply
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestException
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserVaultState
import com.artemchep.keyguard.feature.browserextension.protocol.PairCommitRequest
import com.artemchep.keyguard.feature.browserextension.protocol.PairCommitResponse
import com.artemchep.keyguard.feature.browserextension.protocol.PairRevealRequest
import com.artemchep.keyguard.feature.browserextension.protocol.browserOk
import kotlin.io.encoding.Base64
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The pairing of an unpaired session, README section 8: `pair_commit`, then
 * `pair_reveal`, which always prompts the user. Each `pair_commit` starts a
 * new attempt with fresh nonces; the session shows one prompt at a time.
 * Attempts the user doesn't see are scarce app-wide, see [BrowserPairingAttempts].
 */
@Suppress("LongParameterList") // One dependency per concern of the pairing.
internal class BrowserPairingFlow(
    private val vaultAccess: BrowserVaultAccess,
    private val approvals: BrowserApprovals,
    private val attempts: BrowserPairingAttempts,
    private val requestLog: BrowserRequestLog,
    private val cryptoGenerator: CryptoGenerator,
    private val session: () -> BrowserExtensionSession,
    /** The handshake of the session: its hash and both static keys. */
    private val handshakeHash: ByteArray,
    private val appStatic: ByteArray,
    private val extensionStatic: ByteArray,
) {
    private class Attempt(
        val commit: ByteArray,
        val appNonce: ByteArray,
        val name: String,
        /** Its slot of [attempts], until the user sees its prompt. */
        val slot: BrowserPairingAttempts.Slot,
    )

    private val lock = SynchronizedObject()

    // Guarded by the lock.
    private var attempt: Attempt? = null

    /** Whether an attempt is being revealed, from its `pair_reveal` until it is answered. Guarded by the lock. */
    private var revealing = false

    /** Takes the extension's commitment and answers with the app's nonce. */
    fun commit(request: PairCommitRequest): BrowserReply {
        val commit = request.commit.decodeNonceOrNull()
            ?.takeIf { request.name.countCodePoints() in 1..BrowserProtocol.MAX_NAME_LENGTH }
        return when {
            commit == null -> BrowserReply.Error(BrowserError.INVALID_REQUEST)
            // A locked app pairs nothing.
            vaultAccess.currentState() != BrowserVaultState.UNLOCKED -> BrowserReply.Error(BrowserError.VAULT_LOCKED)
            else -> start(commit, request.name)
        }
    }

    private fun start(commit: ByteArray, name: String): BrowserReply {
        val nonce = cryptoGenerator.seed(BrowserProtocol.NONCE_SIZE)
        val started = synchronized(lock) {
            // One prompt at a time: while an attempt is revealed, another takes no slot.
            val slot = if (!revealing) attempts.take() else null
            if (slot != null) attempt = Attempt(commit, nonce, name, slot)
            slot != null
        }
        return if (started) {
            browserOk(PairCommitResponse(nonce = Base64.encode(nonce)))
        } else {
            BrowserReply.Error(BrowserError.BUSY)
        }
    }

    /**
     * Checks the extension's nonce against its commitment, then asks the user
     * to compare the code and to name the browser. Stores the pairing with
     * [pair] once the user confirms it, and answers with no fields: the
     * session adds the vault's state.
     *
     * @param pair Stores the pairing of the session's extension under a name,
     *   and pairs the session; throws [BrowserRequestException] when it can't.
     */
    suspend fun reveal(
        request: PairRevealRequest,
        pair: suspend (name: String) -> BrowserPairing,
    ): BrowserReply {
        // Each attempt is revealed once, whatever the outcome.
        val attempt = synchronized(lock) {
            attempt?.also {
                attempt = null
                revealing = true
            }
        } ?: return BrowserReply.Error(BrowserError.INVALID_REQUEST)
        return try {
            request.nonce.decodeNonceOrNull()
                ?.let { nonce -> reveal(attempt, nonce, pair) }
                ?: BrowserReply.Error(BrowserError.INVALID_REQUEST)
        } finally {
            synchronized(lock) { revealing = false }
        }
    }

    /** Reveals the [attempt] with the extension's [nonce], and records it in the request log. */
    private suspend fun reveal(
        attempt: Attempt,
        nonce: ByteArray,
        pair: suspend (name: String) -> BrowserPairing,
    ): BrowserReply {
        var paired: BrowserPairing? = null
        return requestLog.recorded(session, { paired }, CliAgentUsageHistoryRequestType.BROWSER_PAIR) {
            when {
                !BrowserPairingCode.matches(attempt.commit, nonce, cryptoGenerator) ->
                    BrowserReply.Error(BrowserError.SECURITY)

                vaultAccess.currentState() != BrowserVaultState.UNLOCKED ->
                    BrowserReply.Error(BrowserError.VAULT_LOCKED)

                else -> {
                    val code = BrowserPairingCode.code(
                        handshakeHash = handshakeHash,
                        appStatic = appStatic,
                        extensionStatic = extensionStatic,
                        appNonce = attempt.appNonce,
                        extensionNonce = nonce,
                        crypto = cryptoGenerator,
                    )
                    prompt(code, suggestedName(attempt.name), attempt.slot, pair) { pairing ->
                        paired = pairing
                    }
                }
            }
        }
    }

    private suspend fun prompt(
        code: String,
        suggestedName: String,
        slot: BrowserPairingAttempts.Slot,
        pair: suspend (name: String) -> BrowserPairing,
        onPaired: (BrowserPairing) -> Unit,
    ): BrowserReply {
        val approval = approvals.ask(
            session = session(),
            pairingName = null,
            operation = BrowserOperation.Pair(code = code, suggestedName = suggestedName),
        )
        // The prompt stayed until the user answered it or it timed out. One
        // the session ended or cancelled may have closed unseen: it counts.
        attempts.release(slot)
        return when (approval) {
            is BrowserApproval.Approved -> {
                val name = approval.name?.boundedName() ?: suggestedName
                // A refusal answers with its error, see [BrowserRequestLog.recorded].
                onPaired(pair(name))
                browserOk()
            }

            is BrowserApproval.Refused -> BrowserReply.Error(approval.error)
        }
    }

    /** The verified browser's name, or else the one the extension suggests. */
    private fun suggestedName(extensionName: String): String {
        val session = session()
        val browser = session.caller.appName
            .ifBlank { session.caller.processName }
            .takeIf { session.verifiedHost }
        return browser?.boundedName()
            ?: extensionName.boundedName()
            ?: extensionName
    }
}

private fun String.decodeNonceOrNull(): ByteArray? = runCatching { Base64.decode(this) }
    .getOrNull()
    ?.takeIf { it.size == BrowserProtocol.NONCE_SIZE }

/**
 * A name of at most [BrowserProtocol.MAX_NAME_LENGTH] characters, or `null`
 * for a blank one. Bounds both the name given while pairing and a new one.
 */
internal fun String.boundedName(): String? {
    val name = trim().takeIf(String::isNotEmpty)
        ?: return null
    var end = 0
    var count = 0
    while (end < name.length && count < BrowserProtocol.MAX_NAME_LENGTH) {
        end += if (name[end].isHighSurrogate() && name.getOrNull(end + 1)?.isLowSurrogate() == true) 2 else 1
        count += 1
    }
    return name.substring(0, end)
}
