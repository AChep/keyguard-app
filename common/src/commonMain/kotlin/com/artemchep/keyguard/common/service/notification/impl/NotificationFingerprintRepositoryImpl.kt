package com.artemchep.keyguard.common.service.notification.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.combineSeq
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DNotificationChannel
import com.artemchep.keyguard.common.model.DNotificationKey
import com.artemchep.keyguard.common.model.DNotificationFingerprint
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.keyvalue.getSerializable
import com.artemchep.keyguard.common.service.notification.NotificationFingerprintRepository
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.time.Duration

class NotificationFingerprintRepositoryImpl(
    private val store: KeyValueStore,
    private val json: Json,
) : NotificationFingerprintRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        store = directDI.instance<Files, KeyValueStore>(arg = Files.NOTIFICATIONS),
        json = directDI.instance(),
    )

    private fun getPref(key: String) = store
        .getSerializable<NotificationFingerprintEntity?>(
            json,
            key,
            defaultValue = null,
        )

    override fun put(
        key: DNotificationKey,
        data: DNotificationFingerprint,
    ): IO<Unit> = ioEffect {
        val keyStr = encodeKey(
            id = key.id,
            tag = key.tag,
        )
        val entity = kotlin.run {
            val timestamp = Clock.System.now()
            NotificationFingerprintEntity(
                timestamp = timestamp,
                channel = data.channel.name
                    .lowercase(),
            )
        }
        getPref(keyStr)
            .setAndCommit(entity)
            .bind()
    }

    override fun getByChannel(
        channel: DNotificationChannel,
    ): IO<List<DNotificationKey>> = store.getAll()
        .effectMap { state ->
            val channelLowercase = channel.name.lowercase()
            state
                .entries
                .mapNotNull { entry ->
                    val data = kotlin.run {
                        val encodedValue = entry.value as? String
                        encodedValue?.let(::decodeValue)
                    } ?: return@mapNotNull null

                    if (data.channel != channelLowercase) {
                        return@mapNotNull null
                    }

                    entry.key
                }
                .mapNotNull { encodedKey ->
                    val parts = encodedKey.split("|")
                    if (parts.size != 2) {
                        return@mapNotNull null
                    }

                    val id = parts[0].toInt()
                    val tag = parts[1].takeUnless { it.isEmpty() }
                    DNotificationKey(
                        id = id,
                        tag = tag,
                    )
                }
        }

    override fun delete(
        key: DNotificationKey,
    ): IO<Unit> = ioEffect {
        val keyStr = encodeKey(
            id = key.id,
            tag = key.tag,
        )
        getPref(keyStr)
            .deleteAndCommit()
            .bind()
    }

    override fun cleanUp(): IO<Unit> = store.getAll()
        .flatMap { state ->
            val now = Clock.System.now()

            // Find all of the notification keys that are
            // obsolete and have to be removed.
            val encodedKeysToRemove = state
                .entries
                .mapNotNull { entry ->
                    val then = kotlin.run {
                        val encodedValue = entry.value as? String
                        encodedValue?.let(::decodeValue)
                            ?.timestamp
                    } ?: return@mapNotNull null

                    val dt = now - then
                    if (dt <= with(Duration) { 7L.days }) {
                        return@mapNotNull null
                    }

                    entry.key
                }
            encodedKeysToRemove
                .map { key ->
                    getPref(key).deleteAndCommit()
                }
                .combineSeq()
                // Hide the result
                .effectMap { }
        }

    private fun encodeKey(
        id: Int,
        tag: String?,
    ): String = "${id}|${tag.orEmpty()}"

    private fun decodeValue(value: String): NotificationFingerprintEntity? {
        return kotlin.runCatching {
            json.decodeFromString<NotificationFingerprintEntity>(value)
        }.getOrNull()
    }
}

@Serializable
private data class NotificationFingerprintEntity(
    val timestamp: Instant,
    val channel: String,
)
