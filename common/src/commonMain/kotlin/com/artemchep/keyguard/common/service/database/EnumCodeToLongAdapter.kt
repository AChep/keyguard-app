package com.artemchep.keyguard.common.service.database

import app.cash.sqldelight.ColumnAdapter

class EnumCodeToLongAdapter<T : Any>(
    private val decoder: (Long) -> T,
    private val encoder: (T) -> Long,
) : ColumnAdapter<T, Long> {
    override fun decode(databaseValue: Long): T = decoder(databaseValue)
    override fun encode(value: T): Long = encoder(value)
}
