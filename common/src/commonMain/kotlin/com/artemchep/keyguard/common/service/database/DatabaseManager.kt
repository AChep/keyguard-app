package com.artemchep.keyguard.common.service.database

import app.cash.sqldelight.ColumnAdapter
import com.artemchep.keyguard.common.io.IO
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.time.Instant

interface DatabaseManager<Database> : DatabaseChangePassword {
    fun get(): IO<Database>

    fun <T> mutate(
        tag: String,
        block: suspend (Database) -> T,
    ): IO<T>
}

//
// Adapter
//

object InstantToLongAdapter : ColumnAdapter<Instant, Long> {
    override fun decode(databaseValue: Long) = Instant.fromEpochMilliseconds(databaseValue)

    override fun encode(value: Instant) = value.toEpochMilliseconds()
}

class ObjectToStringAdapter<T : Any>(
    private val serializer: KSerializer<T>,
    private val json: Json,
) : ColumnAdapter<T, String> {
    companion object {
        @OptIn(InternalSerializationApi::class)
        inline operator fun <reified T : Any> invoke(
            json: Json,
        ) = ObjectToStringAdapter(
            serializer = T::class.serializer(),
            json = json,
        )
    }

    override fun decode(databaseValue: String) = json.decodeFromString(serializer, databaseValue)

    override fun encode(value: T) = json.encodeToString(serializer, value)
}
