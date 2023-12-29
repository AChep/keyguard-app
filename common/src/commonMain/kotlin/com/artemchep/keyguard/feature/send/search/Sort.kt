package com.artemchep.keyguard.feature.send.search

import com.artemchep.keyguard.feature.send.SendItem
import com.artemchep.keyguard.platform.parcelize.LeParcelable

interface PureSendSort : Comparator<SendItem.Item> {
    val id: String
}

interface SendSort : PureSendSort, LeParcelable {
    companion object {
        fun valueOf(
            name: String,
        ): SendSort? = when (name) {
            AlphabeticalSendSort.id -> AlphabeticalSendSort
            AccessCountSendSort.id -> AccessCountSendSort
            LastModifiedSendSort.id -> LastModifiedSendSort
            LastExpiredSendSort.id -> LastExpiredSendSort
            LastDeletedSendSort.id -> LastDeletedSendSort
            else -> null
        }
    }
}
