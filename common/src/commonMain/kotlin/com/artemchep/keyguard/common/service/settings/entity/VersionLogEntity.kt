package com.artemchep.keyguard.common.service.settings.entity

import com.artemchep.keyguard.common.model.AppVersionLog
import kotlin.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class VersionLogEntity(
    val items: List<Item> = emptyList(),
) {
    companion object;

    @Serializable
    data class Item(
        val version: String,
        val ref: String,
        val timestamp: Instant,
    )
}

fun VersionLogEntity.Companion.of(
    log: List<AppVersionLog>,
) = kotlin.run {
    val items = log
        .map { item ->
            VersionLogEntity.Item(
                version = item.version,
                ref = item.ref,
                timestamp = item.timestamp,
            )
        }
    VersionLogEntity(
        items = items,
    )
}

fun VersionLogEntity.toDomain(): List<AppVersionLog> = run {
    val items = items
        .map { item ->
            AppVersionLog(
                version = item.version,
                ref = item.ref,
                timestamp = item.timestamp,
            )
        }
    items
}
