package com.artemchep.keyguard.android.ipc

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.util.foundation.crypto.sha256
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write
import org.jetbrains.compose.resources.StringResource
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

internal object AndroidIpcApprovalCoordinator {
    const val AUTHORIZATION_EXTRA =
        "com.artemchep.keyguard.android.ipc.extra.ONE_SHOT_AUTHORIZATION"

    private const val MAX_PENDING_REQUESTS = 32

    // Pending requests are the only store entries a client can create
    // without any user interaction, so an unbounded caller could saturate
    // the shared capacity and deny approvals to every other app. Sized for
    // the realistic worst case of a few parallel interaction-required
    // results; the lifetime sweep restores quota quickly.
    private const val MAX_PENDING_REQUESTS_PER_CALLER = 4
    private const val LIFETIME_MS = 60_000L

    private val nextRequestCode = AtomicInteger(1)
    private val secureRandom = SecureRandom()

    /**
     * Requests and grants bridge a Binder call, an external Activity, and a
     * later Binder retry. Their owner is therefore the application process,
     * not either bound Service instance. Explicit security events below
     * invalidate them; process death remains the fail-closed lifetime bound.
     */
    private val store = AndroidIpcApprovalStore(
        lifetimeMs = LIFETIME_MS,
        maxPendingRequests = MAX_PENDING_REQUESTS,
        maxPendingRequestsPerCaller = MAX_PENDING_REQUESTS_PER_CALLER,
        elapsedNow = ::elapsedNow,
        randomToken = ::randomToken,
    )

    data class Candidate(
        val id: String,
        val name: String,
        val description: String,
        val preselected: Boolean = false,
    )

    class Request(
        val id: String = randomToken(),
        val caller: AndroidIpcCaller,
        val protocol: String,
        /** Localized display name of [protocol]; never used for matching. */
        val protocolLabel: StringResource,
        val action: String,
        val operation: StringResource,
        val requestDigest: String,
        val retryIntent: Intent,
        val allowMultiple: Boolean,
        val allowEmpty: Boolean = false,
        val registerApp: Boolean = false,
        val requiresAuthentication: Boolean = false,
        val allowTokenlessRetry: Boolean = false,
        val validateCaller: () -> Boolean = { true },
        /**
         * Persists the caller into the connected-apps registry. Invoked when
         * the user approves a [registerApp] request, before the grant is
         * issued, because a client is not obliged to replay the returned
         * intent and its authorization token.
         */
        val registerCaller: suspend () -> Boolean = { true },
        val sessionIdentity: () -> String? = { null },
        val loadCandidates: suspend () -> List<Candidate>,
        val expiresAtElapsedMs: Long = elapsedNow() + LIFETIME_MS,
    )

    data class Snapshot(
        val id: String,
        val appLabel: String,
        val packageName: String,
        val protocolLabel: StringResource,
        val operation: StringResource,
        val allowMultiple: Boolean,
        val allowEmpty: Boolean,
        val registerApp: Boolean,
        val requiresAuthentication: Boolean,
        val candidates: List<Candidate>,
        val expiresAtElapsedMs: Long,
    )

    data class Authorization(
        val approvedKeyIds: Set<String>,
        val registerApp: Boolean,
        val requiresAuthentication: Boolean,
    )

    fun createPendingIntent(
        context: Context,
        request: Request,
    ): PendingIntent? {
        if (!store.add(request)) {
            return null
        }
        val intent = AndroidIpcApprovalActivity.getIntent(
            context = context,
            requestId = request.id,
        )
        return runCatching {
            PendingIntent.getActivity(
                context,
                nextRequestCode.getAndIncrement(),
                intent,
                PendingIntent.FLAG_ONE_SHOT or
                        PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrElse {
            store.deny(request.id)
            null
        }
    }

    suspend fun snapshot(requestId: String): Snapshot? {
        val request = store.get(requestId)
        val candidates = request?.let {
            runCatching {
                it.loadCandidates()
                    .distinctBy(Candidate::id)
            }.getOrNull()
        }
        return request
            ?.takeIf { candidates != null && store.isCurrent(requestId, it) }
            ?.let {
                Snapshot(
                    id = it.id,
                    appLabel = it.caller.appLabel,
                    packageName = it.caller.packageName,
                    protocolLabel = it.protocolLabel,
                    operation = it.operation,
                    allowMultiple = it.allowMultiple,
                    allowEmpty = it.allowEmpty,
                    registerApp = it.registerApp,
                    requiresAuthentication = it.requiresAuthentication,
                    candidates = requireNotNull(candidates),
                    expiresAtElapsedMs = it.expiresAtElapsedMs,
                )
            }
    }

    suspend fun approve(
        requestId: String,
        selectedKeyIds: Set<String>,
    ): Intent? {
        val request = store.get(requestId)
            ?: return null
        val approved = when {
            !request.validateCaller() -> {
                store.deny(requestId)
                false
            }

            selectedKeyIds.isEmpty() && !request.allowEmpty -> false
            // The registration is the user's decision, so it is committed
            // here rather than on the retry: clients such as K-9 re-issue a
            // fresh request after the approval instead of replaying the
            // intent carrying the authorization token.
            request.registerApp && !request.registerCaller() -> {
                store.deny(requestId)
                false
            }

            else -> true
        }
        return if (approved) {
            store.approve(
                requestId = requestId,
                selectedKeyIds = selectedKeyIds,
            )?.let { approval ->
                Intent(approval.request.retryIntent).apply {
                    putExtra(AUTHORIZATION_EXTRA, approval.token)
                }
            }
        } else {
            null
        }
    }

    fun deny(requestId: String) {
        store.deny(requestId)
    }

    fun consume(
        token: String?,
        caller: AndroidIpcCaller,
        protocol: String,
        action: String,
        requestDigest: String,
        sessionIdentity: String?,
    ): Authorization? = store.consume(
        token = token,
        caller = caller,
        protocol = protocol,
        action = action,
        requestDigest = requestDigest,
        sessionIdentity = sessionIdentity,
    )

    fun invalidateProtocol(protocol: String) {
        store.invalidateProtocol(protocol)
    }

    fun invalidatePrivateGrants() {
        store.invalidatePrivateGrants()
    }

    fun invalidateCaller(packageName: String) {
        store.invalidateCaller(packageName)
    }

    private fun elapsedNow(): Long = SystemClock.elapsedRealtime()

    private fun randomToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }
}

internal class AndroidIpcApprovalStore(
    private val lifetimeMs: Long,
    private val maxPendingRequests: Int,
    private val maxPendingRequestsPerCaller: Int,
    private val elapsedNow: () -> Long,
    private val randomToken: () -> String,
) {
    class Approval(
        val request: AndroidIpcApprovalCoordinator.Request,
        val token: String,
    )

    private data class Grant(
        val caller: AndroidIpcCaller,
        val protocol: String,
        val action: String,
        val requestDigest: String,
        val approvedKeyIds: Set<String>,
        val registerApp: Boolean,
        val requiresAuthentication: Boolean,
        val allowTokenlessRetry: Boolean,
        val sessionIdentity: String?,
        val expiresAtElapsedMs: Long,
    ) {
        fun matches(
            caller: AndroidIpcCaller,
            protocol: String,
            action: String,
            requestDigest: String,
            sessionIdentity: String?,
        ): Boolean =
            this.caller.uid == caller.uid &&
                this.caller.pid == caller.pid &&
                this.caller.principal == caller.principal &&
                this.protocol == protocol &&
                this.action == action &&
                this.requestDigest == requestDigest &&
                (
                    !requiresAuthentication ||
                        this.sessionIdentity == sessionIdentity
                    )
    }

    private val requests =
        LinkedHashMap<String, AndroidIpcApprovalCoordinator.Request>()
    private val grants = LinkedHashMap<String, Grant>()

    @Synchronized
    fun add(request: AndroidIpcApprovalCoordinator.Request): Boolean {
        sweepLocked()
        // The per-caller count is keyed by the principal — the identity
        // grants already match on — so a client restarting its process does
        // not mint fresh quota. Grants are excluded: creating one takes a
        // user tap, so they are not a spam vector, and denying a request
        // over grants the user just issued would punish the legit flow.
        val pendingForCaller = requests.values.count {
            it.caller.principal == request.caller.principal
        }
        if (
            requests.size + grants.size >= maxPendingRequests ||
            pendingForCaller >= maxPendingRequestsPerCaller ||
            request.id in requests
        ) {
            return false
        }
        requests[request.id] = request
        return true
    }

    @Synchronized
    fun get(requestId: String): AndroidIpcApprovalCoordinator.Request? {
        sweepLocked()
        return requests[requestId]
    }

    @Synchronized
    fun isCurrent(
        requestId: String,
        request: AndroidIpcApprovalCoordinator.Request,
    ): Boolean {
        sweepLocked()
        return requests[requestId] === request
    }

    @Synchronized
    fun approve(
        requestId: String,
        selectedKeyIds: Set<String>,
    ): Approval? {
        sweepLocked()
        val request = requests.remove(requestId)
            ?: return null
        val token = randomToken()
        grants[token] = Grant(
            caller = request.caller,
            protocol = request.protocol,
            action = request.action,
            requestDigest = request.requestDigest,
            approvedKeyIds = selectedKeyIds,
            registerApp = request.registerApp,
            requiresAuthentication = request.requiresAuthentication,
            allowTokenlessRetry = request.allowTokenlessRetry,
            sessionIdentity = request.sessionIdentity(),
            expiresAtElapsedMs = elapsedNow() + lifetimeMs,
        )
        return Approval(request, token)
    }

    @Synchronized
    fun deny(requestId: String) {
        requests.remove(requestId)
    }

    @Synchronized
    fun consume(
        token: String?,
        caller: AndroidIpcCaller,
        protocol: String,
        action: String,
        requestDigest: String,
        sessionIdentity: String?,
    ): AndroidIpcApprovalCoordinator.Authorization? {
        sweepLocked()
        val grant = if (token != null) {
            grants.remove(token)
        } else {
            grants.entries
                .firstOrNull { (_, candidate) ->
                    candidate.allowTokenlessRetry &&
                            candidate.matches(
                                caller = caller,
                                protocol = protocol,
                                action = action,
                                requestDigest = requestDigest,
                                sessionIdentity = sessionIdentity,
                            )
                }
                ?.let { (candidateToken, candidate) ->
                    grants.remove(candidateToken)
                    candidate
                }
        }
        return grant
            ?.takeIf {
                it.matches(
                    caller = caller,
                    protocol = protocol,
                    action = action,
                    requestDigest = requestDigest,
                    sessionIdentity = sessionIdentity,
                )
            }
            ?.let {
                AndroidIpcApprovalCoordinator.Authorization(
                    approvedKeyIds = it.approvedKeyIds,
                    registerApp = it.registerApp,
                    requiresAuthentication = it.requiresAuthentication,
                )
            }
    }

    @Synchronized
    fun invalidateAll() {
        requests.clear()
        grants.clear()
    }

    @Synchronized
    fun invalidateProtocol(protocol: String) {
        requests.values.removeAll { it.protocol == protocol }
        grants.values.removeAll { it.protocol == protocol }
    }

    @Synchronized
    fun invalidatePrivateGrants() {
        grants.values.removeAll { it.requiresAuthentication }
    }

    @Synchronized
    fun invalidateCaller(packageName: String) {
        requests.values.removeAll {
            it.caller.packageName == packageName
        }
        grants.values.removeAll {
            it.caller.packageName == packageName
        }
    }

    private fun sweepLocked() {
        val now = elapsedNow()
        requests.values.removeAll { it.expiresAtElapsedMs <= now }
        grants.values.removeAll { it.expiresAtElapsedMs <= now }
    }
}

internal fun androidIpcRequestDigest(
    action: String,
    parts: Iterable<String>,
): String {
    // Each part is framed as "<length>\0<bytes>\0" so part boundaries
    // cannot be forged by crafting the part contents.
    val framed = Buffer()
    sequenceOf(action)
        .plus(parts.asSequence())
        .forEach { part ->
            val bytes = part.encodeToByteArray()
            framed.write(bytes.size.toString().encodeToByteArray())
            framed.writeByte(0)
            framed.write(bytes)
            framed.writeByte(0)
        }
    return androidIpcByteDigest(framed.readByteArray())
}

internal fun androidIpcByteDigest(value: ByteArray): String =
    sha256(value).toHex()
