package com.artemchep.keyguard.common.service.review.impl

import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.asInstant
import com.artemchep.keyguard.common.service.keyvalue.setAndCommit
import com.artemchep.keyguard.common.service.review.ReviewLog
import kotlin.time.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class ReviewLogImpl(
    private val store: KeyValueStore,
) : ReviewLog {
    companion object {
        private const val KEY_LAST_REQUESTED_AT = "last_requested_at"

        private const val NONE_INSTANT = -1L
    }

    private val lastRequestedAtPref =
        store.getLong(KEY_LAST_REQUESTED_AT, NONE_INSTANT)

    constructor(directDI: DirectDI) : this(
        store = directDI.instance<Files, KeyValueStore>(arg = Files.REVIEW),
    )

    override fun setLastRequestedAt(
        requestedAt: Instant,
    ) = lastRequestedAtPref
        .setAndCommit(requestedAt)

    override fun getLastRequestedAt() = lastRequestedAtPref.asInstant()
}
