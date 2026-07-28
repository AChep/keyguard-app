package com.artemchep.keyguard.android.sshagent

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred

private const val DEFAULT_PENDING_RESERVATION_TTL_MS = 60_000L

internal val sshAgentBridgeAdmission =
    SshAgentBridgeAdmission(
        nowMs = SystemClock::elapsedRealtime,
    )

/**
 * Process-wide admission shared by the exported receiver and the bridge service.
 *
 * A slot is reserved before asynchronous receiver work begins, becomes active
 * only after the service has registered the bridge job, and is released when
 * that bridge ends. Pending recovery requests expire so notification intents
 * cannot retain capacity indefinitely.
 */
internal class SshAgentBridgeAdmission(
    private val capacity: Int = SshAgentContract.MAX_CONCURRENT_SESSIONS,
    private val nowMs: () -> Long,
) {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    init {
        require(capacity > 0) { "SSH agent bridge capacity must be positive" }
    }

    fun tryReserve(
        sessionId: String,
        pendingTtlMs: Long = DEFAULT_PENDING_RESERVATION_TTL_MS,
    ): ReserveResult =
        synchronized(lock) {
            require(sessionId.isNotEmpty()) { "SSH agent session id must not be empty" }
            require(pendingTtlMs > 0L) { "SSH agent reservation TTL must be positive" }

            val now = nowMs()
            purgeExpiredLocked(now)
            if (sessionId in entries) {
                return@synchronized ReserveResult.Duplicate
            }
            if (entries.size >= capacity) {
                return@synchronized ReserveResult.Busy
            }

            val completion = CompletableDeferred<SshAgentContract.BroadcastOutcome>()
            entries[sessionId] =
                Entry(
                    state = State.Pending,
                    expiresAtMs = now.saturatingAdd(pendingTtlMs),
                    completion = completion,
                )
            ReserveResult.Reserved(
                Reservation(
                    sessionId = sessionId,
                    outcome = completion,
                ),
            )
        }

    fun canStart(sessionId: String): Boolean =
        synchronized(lock) {
            purgeExpiredLocked(nowMs())
            when (entries[sessionId]?.state) {
                State.Pending,
                State.Deferred,
                -> true

                State.Active,
                null,
                -> false
            }
        }

    fun markActive(sessionId: String): Boolean =
        synchronized(lock) {
            purgeExpiredLocked(nowMs())
            val entry =
                entries[sessionId]
                    ?: return@synchronized false
            when (entry.state) {
                State.Pending,
                State.Deferred,
                -> {
                    entry.state = State.Active
                    entry.expiresAtMs = null
                    entry.completion.complete(SshAgentContract.BroadcastOutcome.ACCEPTED)
                    true
                }

                State.Active -> {
                    false
                }
            }
        }

    fun markDeferred(sessionId: String): Boolean =
        synchronized(lock) {
            purgeExpiredLocked(nowMs())
            val entry =
                entries[sessionId]
                    ?: return@synchronized false
            if (entry.state != State.Pending) {
                return@synchronized false
            }

            entry.state = State.Deferred
            entry.completion.complete(SshAgentContract.BroadcastOutcome.DEFERRED)
            true
        }

    fun reject(
        sessionId: String,
        outcome: SshAgentContract.BroadcastOutcome,
    ): Boolean =
        synchronized(lock) {
            require(!outcome.accepted) { "Accepted outcome cannot reject an admission reservation" }
            val entry =
                entries[sessionId]
                    ?: return@synchronized false
            if (entry.state == State.Active) {
                return@synchronized false
            }
            entries.remove(sessionId)
            entry.completion.complete(outcome)
            true
        }

    fun release(sessionId: String): Boolean =
        synchronized(lock) {
            val entry =
                entries.remove(sessionId)
                    ?: return@synchronized false
            entry.completion.complete(SshAgentContract.BroadcastOutcome.START_FAILED)
            true
        }

    private fun purgeExpiredLocked(now: Long) {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val (_, entry) = iterator.next()
            val expiresAtMs =
                entry.expiresAtMs
                    ?: continue
            if (now >= expiresAtMs) {
                iterator.remove()
                entry.completion.complete(SshAgentContract.BroadcastOutcome.START_FAILED)
            }
        }
    }

    internal data class Reservation(
        val sessionId: String,
        val outcome: Deferred<SshAgentContract.BroadcastOutcome>,
    )

    internal sealed interface ReserveResult {
        data class Reserved(
            val reservation: Reservation,
        ) : ReserveResult

        data object Busy : ReserveResult

        data object Duplicate : ReserveResult
    }

    private data class Entry(
        var state: State,
        var expiresAtMs: Long?,
        val completion: CompletableDeferred<SshAgentContract.BroadcastOutcome>,
    )

    private enum class State {
        Pending,
        Deferred,
        Active,
    }
}

private fun Long.saturatingAdd(other: Long): Long =
    if (this > Long.MAX_VALUE - other) {
        Long.MAX_VALUE
    } else {
        this + other
    }
