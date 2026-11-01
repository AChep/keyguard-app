package com.artemchep.keyguard.feature.dragout.compose

import com.artemchep.keyguard.common.service.dragout.PreparedContent
import com.artemchep.keyguard.common.service.dragout.PreparedContentReader
import com.artemchep.keyguard.test.TestByteSnapshot
import com.artemchep.keyguard.util.io.spool.ByteSnapshotReader

/** A prepared file whose retirement callbacks behave like the content store's. */
internal class DragOutTestContent(
    name: String,
    val bytes: ByteArray = byteArrayOf(0, 1, 2, 127, -128, -1),
) {
    private val snapshot = TestByteSnapshot(bytes)
    var handoffs = 0
    var closes = 0
    val readersOpened: Int get() = snapshot.readerOpens
    val readersClosed: Int get() = snapshot.readerCloses
    private var offered = true
    private val retirementActions = mutableListOf<() -> Unit>()

    val content = PreparedContent(
        name = name,
        size = bytes.size.toLong(),
        onOpenReader = {
            check(offered)
            val reader = snapshot.openReader()
            object : PreparedContentReader, ByteSnapshotReader by reader {}
        },
        isOffered = { offered },
        onHandoff = { handoffs++ },
        onClose = { retire() },
        onRetirement = { action ->
            if (offered) retirementActions += action else action()
            AutoCloseable { retirementActions -= action }
        },
    )

    /** Simulates the store's deadline. */
    fun retire() {
        if (!offered) return
        offered = false
        closes++
        retirementActions.toList().also { retirementActions.clear() }.forEach { it() }
    }
}
