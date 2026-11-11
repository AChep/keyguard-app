package com.artemchep.keyguard.feature.p2psync.testing

import com.artemchep.keyguard.feature.p2psync.clock.HlcClock
import com.artemchep.keyguard.feature.p2psync.merge.EntryBudget
import com.artemchep.keyguard.feature.p2psync.merge.EntrySigner
import com.artemchep.keyguard.feature.p2psync.merge.LocalEditPublisher
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import com.artemchep.keyguard.feature.p2psync.model.SyncEntry
import kotlinx.serialization.json.JsonObject

/**
 * TEST ONLY: the edits devices make of one entry of the [kind], each signed as its device
 * signs it. The tests of how the parts of a kind merge build their versions with it.
 */
class EntryEdits(
    private val kind: EntryKind,
) {
    private companion object {
        const val ID = "6f1d2c3b-4a59-4e6f-8a7b-9c0d1e2f3a4b"
    }

    private val publisher = LocalEditPublisher()
    private val crypto = TestCrypto()
    private var time = 1_000_000L

    /** The clock of the device [node]. The devices share one time, which moves on at every read. */
    fun clock(node: String) = HlcClock(node = node, physicalNow = { time++ }, persist = {})

    /** The entry as the device of the [clock] makes it, with the [body]. */
    fun create(body: JsonObject, clock: HlcClock): SyncEntry = publish(current = null, body, clock)

    /** The [current] entry once the device of the [clock] changed its body to the [body]. */
    fun edit(current: SyncEntry, body: JsonObject, clock: HlcClock): SyncEntry = publish(current, body, clock)

    private fun publish(current: SyncEntry?, body: JsonObject, clock: HlcClock): SyncEntry = publisher.publish(
        id = ID,
        kind = kind,
        current = current,
        applied = current?.body,
        local = body,
        clock = clock,
        signer = EntrySigner(crypto, "group", clock.node.encodeToByteArray()),
        budget = EntryBudget.of(emptyList()),
    )
}
