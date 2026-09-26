package com.artemchep.keyguard.common.service.pendinghistory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioUnit

/** Test queue that records the enqueued items and never persists them. */
internal class RecordingPendingUsageHistoryQueue : PendingUsageHistoryQueue {
    val items = mutableListOf<PendingUsageHistory>()

    override fun get(): IO<List<SealedPendingUsageHistory>> = io(emptyList())

    override fun enqueue(item: PendingUsageHistory): IO<Unit> {
        items += item
        return ioUnit()
    }

    override fun remove(id: String): IO<Unit> = ioUnit()
}
