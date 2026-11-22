package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.io.runCatchingUnlessCancelled
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.util.useAndClear
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserCapability
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserError
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserEvent
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserGate
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserMessages
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocol
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserProtocolException
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserReassembler
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserReply
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestEnvelope
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestException
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserRequestType
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserVaultState
import com.artemchep.keyguard.feature.browserextension.protocol.CancelRequest
import com.artemchep.keyguard.feature.browserextension.protocol.HelloRequest
import com.artemchep.keyguard.feature.browserextension.protocol.HelloResponse
import com.artemchep.keyguard.feature.browserextension.protocol.PairRevealRequest
import com.artemchep.keyguard.feature.browserextension.protocol.VaultEvent
import com.artemchep.keyguard.feature.browserextension.protocol.VaultResponse
import com.artemchep.keyguard.feature.browserextension.protocol.browserOk
import com.artemchep.keyguard.feature.generator.PasswordGeneratorConfigReader
import com.artemchep.keyguard.feature.p2psync.transport.ByteStream
import com.artemchep.keyguard.nativecrypto.NativeCryptoException
import com.artemchep.keyguard.provider.keyguard.transport.NoiseByteStream
import com.artemchep.keyguard.provider.keyguard.transport.NoiseSuite
import com.artemchep.keyguard.provider.keyguard.transport.NoiseSuiteException
import com.artemchep.keyguard.provider.keyguard.transport.TransportException
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A reply, made as it is written: what it reads is what the extension learns
 * at that point of the session, see [BrowserSessionWriter.send].
 */
private typealias BrowserAnswer = () -> BrowserReply

/**
 * One extension's session, README sections 2 to 6: the Noise XX handshake as
 * the responder, then the requests of the extension and the app's events,
 * until either side ends the stream, the user removes the pairing, or the
 * integration turns off.
 */
@Suppress("TooManyFunctions", "LongParameterList") // One function per step of the session, one dependency per concern.
internal class BrowserSession(
    initial: BrowserExtensionSession,
    private val pairings: BrowserPairings,
    private val vaultAccess: BrowserVaultAccess,
    private val approvals: BrowserApprovals,
    private val pairingAttempts: BrowserPairingAttempts,
    private val requestLog: BrowserRequestLog,
    saveWindows: BrowserSaveWindows,
    private val cryptoGenerator: CryptoGenerator,
    tldService: TldService,
    passwordGenerator: PasswordGeneratorConfigReader,
    passkeys: BrowserPasskeys,
    private val logRepository: LogRepository,
    /** Whether the integration serves extensions; the session ends once it does not. */
    private val active: Flow<Boolean>,
    /** Shows the session as paired by a pairing. */
    private val onPaired: (sessionId: String, pairingId: String) -> Unit,
    /** How long the handshake may take. */
    private val handshakeTimeoutMs: Long,
) {
    private val session = MutableStateFlow(initial)
    private val pairing = MutableStateFlow<BrowserPairing?>(null)

    private val handles = BrowserHandles(cryptoGenerator)

    private val operations = BrowserVaultOperations(
        vaultAccess = vaultAccess,
        approvals = approvals,
        saveWindows = saveWindows,
        requestLog = requestLog,
        cryptoGenerator = cryptoGenerator,
        tldService = tldService,
        passwordGenerator = passwordGenerator,
        passkeys = passkeys,
        logRepository = logRepository,
        session = { session.value },
        pairing = { pairing.value },
        handles = handles,
    )

    private val ceremony = BrowserPasskeyCeremony(
        vaultAccess = vaultAccess,
        approvals = approvals,
        requestLog = requestLog,
        passkeys = passkeys,
        cryptoGenerator = cryptoGenerator,
        tldService = tldService,
        handles = handles,
        session = { session.value },
        pairing = { pairing.value },
        requirePairing = ::requirePairing,
        awaitUnlock = ::awaitUnlock,
    )

    /** Held while the session stores a pairing of its own, see [awaitRevocation]. */
    private val pairingMutex = Mutex()

    /** The extension's static key, once the handshake ends. */
    private lateinit var extensionStatic: ByteArray

    private val lock = SynchronizedObject()

    /** The requests in flight, by id. Guarded by the lock. */
    private val inFlight = mutableMapOf<Long, InFlight>()

    /** What `hello` agreed on, or `null` before a successful one. Guarded by the lock. */
    private var agreement: Agreement? = null
    private var unlocking = false

    /** The unlock prompt that the requests of the session share, see [awaitUnlock]. Guarded by the lock. */
    private var sharedUnlock: SharedUnlock? = null

    /** The scope of the session's coroutines, once it serves requests. */
    private lateinit var sessionScope: CoroutineScope

    /** Whether the session is ending: it drops the requests that come. Guarded by the lock. */
    private var closing = false

    // Guarded by the writer: the vault state the extension last learned.
    private var reportedVault: BrowserVaultState? = null

    /** Serves the session over [stream]; ends quietly when the extension breaks the protocol. */
    suspend fun run(stream: ByteStream) {
        // An unreadable key ends the session before its handshake.
        val keyPair = pairings.appKeyPair()
            ?: return
        val noise = handshake(stream, keyPair.privateKey)
            ?: return
        try {
            extensionStatic = noise.remoteStatic
            val flow = BrowserPairingFlow(
                vaultAccess = vaultAccess,
                approvals = approvals,
                attempts = pairingAttempts,
                requestLog = requestLog,
                cryptoGenerator = cryptoGenerator,
                session = { session.value },
                handshakeHash = noise.handshakeHash,
                appStatic = keyPair.publicKey,
                extensionStatic = noise.remoteStatic,
            )
            serve(noise, BrowserSessionWriter(noise), flow)
        } catch (_: TransportException) {
            // The stream ended.
        } catch (_: BrowserProtocolException) {
            // The extension broke the protocol.
        } finally {
            noise.close()
        }
    }

    /** The encrypted stream, or `null` when the handshake fails; then the stream is closed. */
    private suspend fun handshake(stream: ByteStream, privateKey: ByteArray): NoiseByteStream? = try {
        NoiseByteStream.handshake(
            stream = stream,
            suite = NoiseSuite.XX,
            initiator = false,
            localPrivateKey = privateKey,
            prologue = BrowserProtocol.prologue(session.value.origin),
            timeoutMs = handshakeTimeoutMs,
        )
    } catch (_: TransportException) {
        null
    } catch (_: NoiseSuiteException) {
        // Another suite ID: the session ends without a reply.
        null
    } catch (_: NativeCryptoException) {
        null
    }

    private suspend fun serve(
        noise: NoiseByteStream,
        writer: BrowserSessionWriter,
        flow: BrowserPairingFlow,
    ) = coroutineScope {
        sessionScope = this
        val ended = CompletableDeferred<Unit>()
        launch {
            read(noise, writer, flow)
            ended.complete(Unit)
        }
        // Each event ends the session: nothing is written after it.
        launch {
            active.first { !it }
            close(writer) { if (pairing.value != null) BrowserMessages.event(BrowserEvent.DISABLED) else null }
            ended.complete(Unit)
        }
        launch {
            val revoked = awaitRevocation()
            close(writer) { if (revoked) BrowserMessages.event(BrowserEvent.REVOKED) else null }
            ended.complete(Unit)
        }
        launch { pushVaultEvents(writer) }
        launch { followPairingName() }
        ended.await()
        coroutineContext.cancelChildren()
    }

    /**
     * Writes the last message of the session that [build] makes. First stops
     * every request in flight, which takes its prompts and windows off the
     * screen: a stalled write may hold the writer until the stream gives up.
     */
    private suspend fun close(writer: BrowserSessionWriter, build: () -> ByteArray?) {
        val handlings = synchronized(lock) {
            closing = true
            inFlight.values.map { it.handling }
        }
        handlings.forEach { it.cancel() }
        writer.close(build)
    }

    private suspend fun CoroutineScope.read(
        noise: NoiseByteStream,
        writer: BrowserSessionWriter,
        flow: BrowserPairingFlow,
    ) {
        val reassembler = BrowserReassembler()
        try {
            while (true) {
                val fragment = noise.read()
                    ?: break
                // Null while more fragments of the message follow.
                val message = fragment.useAndClear(reassembler::add)
                if (message != null) {
                    val request = message.useAndClear(BrowserMessages::parseRequest)
                    accept(request, writer, flow)
                }
            }
        } finally {
            reassembler.clear()
        }
    }

    /**
     * Answers [request] at once, or starts it. `hello` and `cancel` are
     * answered before the next request is read, and are never in flight.
     * Once the session is closing, drops the request.
     */
    private suspend fun CoroutineScope.accept(
        request: BrowserRequestEnvelope,
        writer: BrowserSessionWriter,
        flow: BrowserPairingFlow,
    ) {
        val type = request.type?.let(BrowserRequestType::of)
        val pairingError = when (type) {
            null, BrowserRequestType.HELLO, BrowserRequestType.CANCEL -> null
            else -> pairingError()
        }
        val refusal = synchronized(lock) {
            if (closing) return
            if (request.id in inFlight) throw BrowserProtocolException("A request id in flight")
            when (type) {
                // A session says `hello` once; a failed one may be retried.
                BrowserRequestType.HELLO -> BrowserError.INVALID_REQUEST.takeIf { agreement != null }
                // The extension sends `hello` first.
                BrowserRequestType.CANCEL -> BrowserError.INVALID_REQUEST.takeIf { agreement == null }
                else -> refusalOf(request.type, type, pairingError)
            }
        }
        when {
            refusal != null -> writer.respond(request.id, BrowserReply.Error(refusal).answer())
            type == BrowserRequestType.HELLO -> writer.respond(request.id, hello(request))
            type == BrowserRequestType.CANCEL -> writer.respond(request.id, cancel(request))
            type != null -> start(type, request, writer, flow)
        }
    }

    /**
     * Serves the [request] in flight. It is in flight before the next request
     * is read, so that a `cancel` that follows finds it.
     */
    private fun CoroutineScope.start(
        type: BrowserRequestType,
        request: BrowserRequestEnvelope,
        writer: BrowserSessionWriter,
        flow: BrowserPairingFlow,
    ) {
        val entry = synchronized(lock) {
            // The session began closing since the request was accepted.
            if (closing) return
            InFlight(async(start = CoroutineStart.LAZY) { handle(type, request, flow) })
                .also { inFlight[request.id] = it }
        }
        launch {
            try {
                val answer = try {
                    entry.handling.await()
                } catch (_: CancellationException) {
                    // The session ended.
                    currentCoroutineContext().ensureActive()
                    // The extension cancelled the request, or the session is
                    // closing. A request that committed a change answers it.
                    synchronized(lock) { entry.committed }
                        ?: BrowserReply.Error(BrowserError.CANCELLED).answer()
                }
                writer.send {
                    // Out of flight before the extension can read the answer
                    // and send its next request. A `cancel` that came after
                    // the handling ended still cancels the answer, unless the
                    // request committed a change.
                    val cancelled = retire(request.id, entry)
                        ?: return@send null
                    val reply = if (cancelled) BrowserReply.Error(BrowserError.CANCELLED) else answer()
                    BrowserMessages.response(request.id, reply)
                }
            } finally {
                // The session ended before the answer was written.
                retire(request.id, entry)
            }
        }
    }

    /**
     * Takes the request [id] out of flight, unless it already is: then the
     * extension may have sent a new request with that id. Returns whether the
     * extension cancelled the request before it committed a change, or `null`
     * once the session is closing: ending the session stops every request
     * without an answer.
     */
    private fun retire(id: Long, entry: InFlight): Boolean? = synchronized(lock) {
        if (inFlight[id] === entry) inFlight -= id
        (entry.cancelled && entry.committed == null).takeUnless { closing }
    }

    /**
     * Stops the handling of the request that `cancel` names, if it is in
     * flight: its prompts and windows close, and it answers `cancelled`.
     */
    private fun cancel(request: BrowserRequestEnvelope): BrowserAnswer {
        val target = try {
            request.decode<CancelRequest>().target
        } catch (e: BrowserRequestException) {
            return BrowserReply.Error(e.code).answer()
        }
        val handling = synchronized(lock) {
            inFlight[target]?.let { entry ->
                entry.cancelled = true
                entry.handling
            }
        }
        handling?.cancel()
        return browserOk().answer()
    }

    /**
     * Why the session refuses a request of [type], or `null` when it serves
     * it. Runs under the lock.
     */
    private fun refusalOf(name: String?, type: BrowserRequestType?, pairingError: BrowserError?): BrowserError? {
        val agreed = agreement?.capabilities
        return when {
            // The extension sends `hello` first.
            agreed == null -> BrowserError.INVALID_REQUEST
            inFlight.size >= BrowserProtocol.MAX_IN_FLIGHT -> BrowserError.BUSY
            name == null -> BrowserError.INVALID_REQUEST
            type == null -> BrowserError.NOT_SUPPORTED
            type.capability != null && type.capability !in agreed -> BrowserError.NOT_SUPPORTED
            pairingError != null && pairingError != BrowserError.UNPAIRED -> pairingError
            else -> type.gate.check(
                paired = pairingError == null,
                unlocked = vaultAccess.currentState() == BrowserVaultState.UNLOCKED,
            )
        }
    }

    private suspend fun hello(request: BrowserRequestEnvelope): BrowserAnswer {
        val hello: HelloRequest
        val version: Int
        val paired: Boolean
        try {
            hello = request.decode<HelloRequest>()
            version = BrowserProtocol.negotiate(hello.protocol)
                ?: throw BrowserRequestException(BrowserError.NOT_SUPPORTED)
            paired = resumePairing()
        } catch (e: BrowserRequestException) {
            return BrowserReply.Error(e.code).answer()
        }
        val agreed = hello.capabilities.mapNotNull(BrowserCapability::of).toSet() intersect SUPPORTED_CAPABILITIES
        synchronized(lock) { agreement = Agreement(version, agreed) }
        return {
            browserOk(
                HelloResponse(
                    // The lower of both sides' highest versions, one the app speaks.
                    protocol = version,
                    capabilities = agreed.map { it.wireName },
                    paired = paired,
                    vault = reportVault(),
                ),
            )
        }
    }

    /**
     * A failed lookup leaves hello retryable and the extension's pin intact.
     * A pairing that the unlocked vault doesn't confirm, as after a reset of
     * the vault, neither pairs the session nor counts as used. A failed note
     * of the use, as on a full disk, keeps the session paired.
     */
    private suspend fun resumePairing(): Boolean {
        val found = pairings.find(extensionStatic, session.value.origin)
        val error = pairings.confirmationError(found?.id, extensionStatic, session.value.origin)
        if (error != null && error != BrowserError.UNPAIRED) throw BrowserRequestException(error)
        if (found != null && error == null) {
            markPaired(found)
            runCatchingUnlessCancelled { pairings.touch(found.id) }
                .onFailure { e ->
                    // The class only: the message may hold a path.
                    val message = "Failed to note the use of a pairing: ${e::class.simpleName}"
                    logRepository.post(TAG, message, LogLevel.WARNING)
                }
        }
        return error == null
    }

    /** Answers [request]; an unexpected failure answers `not_allowed`, so that the session goes on. */
    @Suppress("TooGenericExceptionCaught") // An unexpected failure must not end the session.
    private suspend fun handle(
        type: BrowserRequestType,
        request: BrowserRequestEnvelope,
        flow: BrowserPairingFlow,
    ): BrowserAnswer = try {
        // The vault may have unlocked since the gate: it confirms the pairing.
        if (type.gate.needsPairing) requirePairing()
        dispatch(type, request, flow)
    } catch (e: BrowserRequestException) {
        BrowserReply.Error(e.code).answer()
    } catch (e: Exception) {
        e.throwIfFatalOrCancellation()
        // The class only: the message may hold a secret.
        logRepository.post(TAG, "Failed to answer a request: ${e::class.simpleName}", LogLevel.ERROR)
        BrowserReply.Error(BrowserError.NOT_ALLOWED).answer()
    }

    private suspend fun dispatch(
        type: BrowserRequestType,
        request: BrowserRequestEnvelope,
        flow: BrowserPairingFlow,
    ): BrowserAnswer = when (type) {
        BrowserRequestType.PAIR_COMMIT -> flow.commit(request.decode()).answer()
        BrowserRequestType.PAIR_REVEAL -> reveal(flow, request.decode(), request.id)
        BrowserRequestType.UNLOCK -> unlock()
        BrowserRequestType.SUGGEST -> operations.suggest(request.decode()).answer()
        BrowserRequestType.SEARCH -> operations.search(request.decode()).answer()
        BrowserRequestType.FILL -> operations.fill(request.decode()).answer()
        BrowserRequestType.TOTP -> operations.totp(request.decode()).answer()
        BrowserRequestType.GENERATE -> operations.generate(request.decode()).answer()
        BrowserRequestType.SAVE -> operations.save(request.decode()).answer()
        BrowserRequestType.NEVER_SAVE -> operations.neverSave(request.decode()).answer()
        BrowserRequestType.PASSKEY_GET -> ceremony.get(request.decode()).answer()
        BrowserRequestType.PASSKEY_CREATE -> ceremony.create(request.decode()).answer()
        // Answered before it is read.
        BrowserRequestType.HELLO,
        BrowserRequestType.CANCEL,
        -> BrowserReply.Error(BrowserError.INVALID_REQUEST).answer()
    }

    /**
     * Pairs the session in the request [id]; then the answer reports the
     * vault's state as it is written.
     */
    private suspend fun reveal(flow: BrowserPairingFlow, request: PairRevealRequest, id: Long): BrowserAnswer =
        when (val reply = flow.reveal(request) { name -> storePairing(name, id) }) {
            is BrowserReply.Ok -> ::vaultReply
            is BrowserReply.Error -> reply.answer()
        }

    /**
     * Shows the unlock prompt and answers once the vault is unlocked, at
     * once if it is already. One unlock prompt per session at a time.
     */
    private suspend fun unlock(): BrowserAnswer {
        val busy = synchronized(lock) {
            unlocking.also { unlocking = true }
        }
        if (busy) return BrowserReply.Error(BrowserError.BUSY).answer()
        // Only a request that shows the prompt is logged.
        val prompted = vaultAccess.currentState() != BrowserVaultState.UNLOCKED
        val approval = try {
            awaitUnlock()
        } catch (e: CancellationException) {
            // The session ended before the request was answered.
            if (prompted) recordUnlock(error = null, cancelled = true)
            throw e
        } finally {
            synchronized(lock) { unlocking = false }
        }
        val error = when (approval) {
            is BrowserApproval.Refused -> approval.error
            // Unlocked, or approved: the answer reports the vault as it is.
            // An unlocked vault must confirm the pairing.
            null, is BrowserApproval.Approved -> pairingError()
        }
        if (prompted) recordUnlock(error = error, cancelled = false)
        return error?.let { BrowserReply.Error(it).answer() } ?: ::vaultReply
    }

    private fun recordUnlock(error: BrowserError?, cancelled: Boolean) = requestLog.record(
        session = session.value,
        pairingName = pairing.value?.name,
        request = CliAgentUsageHistoryRequestType.BROWSER_UNLOCK,
        error = error,
        cancelled = cancelled,
    )

    /**
     * Waits until the vault unlocks or the user answers its prompt; `null`
     * once it unlocks. The requests of the session that wait meanwhile share
     * one prompt, which closes once none of them waits.
     */
    private suspend fun awaitUnlock(): BrowserApproval? {
        if (vaultAccess.currentState() == BrowserVaultState.UNLOCKED) return null
        val shared = synchronized(lock) {
            // A prompt that the user answered serves only the requests that waited for it.
            val current = sharedUnlock?.takeUnless { it.result.isCompleted }
                ?: SharedUnlock(sessionScope.async { runCatchingUnlessCancelled { promptUnlock() } })
                    .also { sharedUnlock = it }
            current.also { it.waiters += 1 }
        }
        try {
            return shared.result.await().getOrThrow()
        } finally {
            val last = synchronized(lock) {
                shared.waiters -= 1
                val none = shared.waiters == 0
                if (none && sharedUnlock === shared) sharedUnlock = null
                none
            }
            // Takes the prompt off the screen before the request is answered.
            if (last) withContext(NonCancellable) { shared.result.cancelAndJoin() }
        }
    }

    /** Shows the unlock prompt until the vault unlocks or the user answers it; `null` once it unlocks. */
    private suspend fun promptUnlock(): BrowserApproval? = coroutineScope {
        val prompt = async {
            approvals.ask(session.value, pairing.value?.name, BrowserOperation.Unlock)
        }
        val unlocked = async {
            vaultAccess.state.first { it == BrowserVaultState.UNLOCKED }
        }
        val result = select<BrowserApproval?> {
            prompt.onAwait { it }
            unlocked.onAwait { null }
        }
        // Takes the prompt off the screen.
        coroutineContext.cancelChildren()
        result
    }

    /** Pushes the changes of the vault's state to a paired session. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun pushVaultEvents(writer: BrowserSessionWriter) {
        pairing
            .flatMapLatest { if (it != null) vaultAccess.state else emptyFlow() }
            .collect {
                // An unlocked vault must confirm the pairing.
                if (pairingError() != null) return@collect
                writer.send {
                    val reported = reportedVault
                    val current = vaultAccess.currentState()
                    // Before `hello`, its answer reports the state.
                    if (reported != null && reported != current) {
                        reportedVault = current
                        BrowserMessages.event(BrowserEvent.VAULT, browserOk(VaultEvent(current)).fields)
                    } else {
                        null
                    }
                }
            }
    }

    /**
     * Follows the stored name of the pairing, which the user may change in
     * the settings: the prompts and the request log show it.
     */
    private suspend fun followPairingName() {
        combine(pairing.map { it?.id }.distinctUntilChanged(), pairings.stored) { id, records ->
            records?.firstOrNull { it.pairing.id == id }?.pairing
        }
            .filterNotNull()
            .collect { stored ->
                // The session may have paired again since.
                pairing.update { current ->
                    if (current?.id == stored.id) current.copy(name = stored.name) else current
                }
            }
    }

    /** Ends the session on removal or an unreadable store. Only a confirmed removal sends `revoked`. */
    private suspend fun awaitRevocation(): Boolean {
        while (true) {
            val id = pairing.filterNotNull().first().id
            val records = pairings.stored.first { records -> records == null || records.none { it.pairing.id == id } }
            val replaced = pairingMutex.withLock { pairing.value?.id != id }
            if (!replaced) return records != null
        }
    }

    private suspend fun pairingError(): BrowserError? =
        pairings.confirmationError(pairing.value?.id, extensionStatic, session.value.origin)

    private suspend fun requirePairing() {
        pairingError()?.let { throw BrowserRequestException(it) }
    }

    /**
     * Stores the pairing of the extension's static key, named [name], and
     * pairs the session with it; it replaces an unconfirmed pairing. A
     * `cancel` doesn't stop the store, and once it is done, the request [id]
     * answers with the vault's state: the pairing stands.
     */
    private suspend fun storePairing(name: String, id: Long): BrowserPairing = withContext(NonCancellable) {
        pairingMutex.withLock {
            pairings.add(name = name, publicKey = extensionStatic, origin = session.value.origin)
                .also { stored ->
                    markPaired(stored)
                    synchronized(lock) { inFlight[id]?.committed = ::vaultReply }
                }
        }
    }

    private fun markPaired(found: BrowserPairing) {
        pairing.value = found
        session.value = session.value.copy(pairingId = found.id)
        onPaired(session.value.id, found.id)
    }

    private fun vaultReply(): BrowserReply = browserOk(VaultResponse(vault = reportVault()))

    /** The vault's state now, which the extension learns with the message being written. */
    private fun reportVault(): BrowserVaultState = vaultAccess.currentState().also { reportedVault = it }

    /** A request in flight. */
    private class InFlight(
        /** Its handling, which `cancel` stops. */
        val handling: Deferred<BrowserAnswer>,
    ) {
        /** Whether the extension cancelled the request. Guarded by the lock of the session. */
        var cancelled = false

        /**
         * The answer of a change that a `cancel` no longer undoes, such as a
         * stored pairing. Guarded by the lock of the session.
         */
        var committed: BrowserAnswer? = null
    }

    /** What the extension and the app agreed on in `hello`. */
    private class Agreement(
        /** The protocol version of the session. */
        val version: Int,
        val capabilities: Set<BrowserCapability>,
    )

    /** An unlock prompt, and the requests of the session that wait for it. */
    private class SharedUnlock(
        val result: Deferred<Result<BrowserApproval?>>,
    ) {
        /** Guarded by the lock of the session. */
        var waiters = 0
    }

    private companion object {
        const val TAG = "BrowserSession"

        /** The optional features this version of the app serves. */
        val SUPPORTED_CAPABILITIES = setOf(BrowserCapability.PASSKEYS, BrowserCapability.SAVE)
    }
}

/** Whether the gate needs a paired session: while the vault is unlocked, one the vault confirms. */
private val BrowserGate.needsPairing: Boolean
    get() = when (this) {
        BrowserGate.PAIRED, BrowserGate.UNLOCKED, BrowserGate.APPROVAL -> true
        BrowserGate.NONE, BrowserGate.UNPAIRED -> false
    }

private fun BrowserReply.answer(): BrowserAnswer = { this }

private suspend fun BrowserSessionWriter.respond(id: Long, answer: BrowserAnswer) = send {
    BrowserMessages.response(id, answer())
}
