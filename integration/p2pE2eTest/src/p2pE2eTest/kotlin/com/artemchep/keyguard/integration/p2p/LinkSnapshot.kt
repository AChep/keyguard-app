package com.artemchep.keyguard.integration.p2p

import com.artemchep.keyguard.common.model.DAppLinkState
import com.artemchep.keyguard.common.model.DKeyguardPeerStatus
import com.artemchep.keyguard.common.model.DKeyguardTransport
import com.artemchep.keyguard.feature.p2psync.model.Revocation
import com.artemchep.keyguard.provider.keyguard.session.storedValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** An email forwarder as its row holds it; the creation date stays local. */
internal data class Forwarder(
    val name: String,
    val type: String,
    val data: Map<String, String>,
)

/**
 * What one app holds of its link: its email forwarders, the sync state of every entry and
 * the roster. Apps that converged hold equal snapshots, and merged everything they received.
 */
internal data class LinkSnapshot(
    /** By sync id, the id of the forwarder on every linked app. */
    val forwarders: Map<String, Forwarder>,
    val entries: Map<String, EntryState>,
    val roster: Map<String, RosterState>,
    /** Envelopes from other devices that no sync pass merged yet. */
    val pendingEnvelopes: Int,
) {
    /** What every app of the link must agree on. */
    fun shared(): LinkSnapshot = copy(pendingEnvelopes = 0)

    /** The names of the forwarders, sorted: a forwarder linked twice shows up twice. */
    fun names(): List<String> = forwarders.values.map { it.name }.sorted()

    /** The id of the forwarder named [name]. */
    fun idOf(name: String): String = forwarders.entries.single { it.value.name == name }.key

    fun forwarder(id: String): Forwarder? = forwarders[id]

    /** The devices the roster lists and nobody revoked. */
    fun activeDevices(): Set<String> = roster
        .filterValues { it.record != null && it.revocations.isEmpty() }
        .keys
}

/** The sync state of one entry; equal on every app once they converged. */
internal data class EntryState(
    val kind: String,
    val versions: JsonElement,
    val deletes: JsonElement,
    val deleted: Boolean,
)

/** The roster record of one device, and its revocations. */
internal data class RosterState(
    val record: JsonElement?,
    val revocations: Set<Revocation>,
)

/**
 * Waits until every one of [devices] holds the same forwarders and link with nothing left
 * to merge, then returns the snapshot they agree on.
 */
internal suspend fun awaitConverged(
    vararg devices: P2pE2eDevice,
    timeout: Duration = CONVERGENCE_TIMEOUT,
): LinkSnapshot {
    val started = TimeSource.Monotonic.markNow()
    var last: List<LinkSnapshot> = emptyList()
    val converged = withTimeoutOrNull(timeout) {
        var agreed: LinkSnapshot? = null
        while (agreed == null) {
            last = devices.map { it.snapshot() }
            val first = last.first()
            if (last.all { it.pendingEnvelopes == 0 && it.shared() == first.shared() }) {
                agreed = first
            } else {
                delay(POLL_MS)
            }
        }
        agreed
    }
    if (converged != null) {
        println("p2p-e2e: ${devices.joinToString { it.name }} converged in ${started.elapsedNow()}")
        return converged
    }
    fail(
        buildString {
            appendLine("The devices did not converge in $timeout.")
            devices.zip(last).forEach { (device, snapshot) ->
                appendLine("${device.name}: $snapshot")
            }
        },
    )
}

/** Waits until [predicate] holds, polling. */
internal suspend fun eventually(
    message: String,
    timeout: Duration = CONVERGENCE_TIMEOUT,
    predicate: suspend () -> Boolean,
) {
    withTimeoutOrNull(timeout) {
        while (!predicate()) delay(POLL_MS)
    } ?: fail("$message, within $timeout")
}

/** Waits until [device] is in the link [state]. */
internal suspend fun awaitLinkState(
    device: P2pE2eDevice,
    state: DAppLinkState,
) {
    var last: DAppLinkState? = null
    withTimeoutOrNull(CONVERGENCE_TIMEOUT) {
        while (true) {
            last = device.linkState()
            if (last == state) break
            delay(POLL_MS)
        }
    } ?: fail("${device.name} is not $state within $CONVERGENCE_TIMEOUT; it is $last")
}

/** Waits until [a] and [b] both recorded that their last session ran over [path]. */
internal suspend fun awaitTransport(
    a: P2pE2eDevice,
    b: P2pE2eDevice,
    path: DKeyguardTransport,
) {
    val started = TimeSource.Monotonic.markNow()
    val aId = a.deviceId()
    val bId = b.deviceId()
    var seen: Pair<String?, String?> = null to null
    withTimeoutOrNull(CONVERGENCE_TIMEOUT) {
        while (true) {
            seen = a.lastTransport(bId) to b.lastTransport(aId)
            if (seen.first == path.storedValue && seen.second == path.storedValue) break
            delay(POLL_MS)
        }
    } ?: fail("${a.name} and ${b.name} did not sync over ${path.storedValue}; last transports: $seen")
    println("p2p-e2e: ${a.name} and ${b.name} synced over ${path.storedValue} after ${started.elapsedNow()}")
}

/** Waits until the live sessions of [a] and [b] hold a session with each other. */
internal suspend fun awaitConnected(
    a: P2pE2eDevice,
    b: P2pE2eDevice,
) {
    val aId = a.deviceId()
    val bId = b.deviceId()
    withTimeoutOrNull(CONVERGENCE_TIMEOUT) {
        a.status().filterNotNull().first { it.peers[bId] is DKeyguardPeerStatus.Connection.Connected }
        b.status().filterNotNull().first { it.peers[aId] is DKeyguardPeerStatus.Connection.Connected }
    } ?: fail("${a.name} and ${b.name} did not connect within $CONVERGENCE_TIMEOUT")
}

internal val CONVERGENCE_TIMEOUT: Duration = 60.seconds

private const val POLL_MS = 200L
