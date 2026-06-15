package com.artemchep.keyguard.common.service.database

import app.cash.sqldelight.ColumnAdapter
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType

object SshUsageHistoryRequestTypeToLongAdapter : ColumnAdapter<SshUsageHistoryRequestType, Long> {
    override fun decode(databaseValue: Long): SshUsageHistoryRequestType =
        SshUsageHistoryRequestType.of(databaseValue)

    override fun encode(value: SshUsageHistoryRequestType): Long =
        value.code
}

object SshUsageHistoryResponseTypeToLongAdapter : ColumnAdapter<SshUsageHistoryResponseType, Long> {
    override fun decode(databaseValue: Long): SshUsageHistoryResponseType =
        SshUsageHistoryResponseType.of(databaseValue)

    override fun encode(value: SshUsageHistoryResponseType): Long =
        value.code
}
