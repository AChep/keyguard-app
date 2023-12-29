package com.artemchep.keyguard.common.service.vault.impl

import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import com.artemchep.keyguard.common.service.vault.SessionMetadataReadWriteRepository
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class SessionMetadataRepositoryImpl(
    private val store: KeyValueStore,
) : SessionMetadataReadWriteRepository {
    companion object {
        private const val KEY_LAST_PASSWORD_USE_TIMESTAMP = "last_password_use_timestamp"

        private const val NONE_INSTANT = -1L
    }

    private val lastPasswordUseTimestamp =
        store.getLong(KEY_LAST_PASSWORD_USE_TIMESTAMP, NONE_INSTANT)

    constructor(directDI: DirectDI) : this(
        store = directDI.instance<Files, KeyValueStore>(arg = Files.SESSION_METADATA),
    )

    override fun setLastPasswordUseTimestamp(instant: Instant?) = lastPasswordUseTimestamp
        .setAndCommit(instant)

    override fun getLastPasswordUseTimestamp() = lastPasswordUseTimestamp
        .asInstant()

    private fun KeyValuePreference<Long>.setAndCommit(instant: Instant?) = io(instant)
        .map { instant ->
            instant?.toEpochMilliseconds()
                ?: NONE_INSTANT
        }
        .flatMap(this::setAndCommit)

    private fun KeyValuePreference<Long>.asInstant() = this
        .map { millis ->
            millis.takeUnless { it == NONE_INSTANT }
                ?.let(Instant::fromEpochMilliseconds)
        }
}
