package com.artemchep.keyguard.common.service.shortcut

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.GlobalShortcutAction
import com.artemchep.keyguard.common.model.GlobalShortcutConfig
import com.artemchep.keyguard.common.model.GlobalShortcutsConfig
import com.artemchep.keyguard.common.service.keyvalue.KeyValuePreference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

internal class GlobalShortcutsConfigState(
    private val preference: KeyValuePreference<String>,
    private val json: Json,
) {
    private val mutex = Mutex()

    // A failed write can stay published until it is rolled back. Read behind the write
    // mutex so observers never activate a provisional shortcut.
    fun config(): Flow<GlobalShortcutsConfig> =
        preference
            .map {
                mutex.withLock {
                    preference.first()
                }
            }.distinctUntilChanged()
            .map(::decode)

    private fun decode(raw: String): GlobalShortcutsConfig {
        runCatchingNonFatal { json.decodeFromString<GlobalShortcutsConfig>(raw) }
            .onSuccess { return it }
        // Unknown IDs or malformed custom bindings must never activate unrelated defaults,
        // so only the entries this version can read stay as stored. [put] keeps the rest.
        val entries = parse(raw)
        return GlobalShortcutAction.entries.fold(GlobalShortcutsConfig()) { config, action ->
            val value =
                entries
                    ?.get(GlobalShortcutsConfig.serialName(action))
                    ?.let { runCatchingNonFatal { json.decodeFromJsonElement<GlobalShortcutConfig>(it) }.getOrNull() }
                    ?: GlobalShortcutConfig(enabled = false)
            config.with(action, value)
        }
    }

    /**
     * Saves the [action]'s entry. Other stored entries are written back as they are, so
     * data this version cannot read survives; missing ones are written as shown, so they
     * do not turn into defaults once the value becomes readable.
     */
    fun put(
        action: GlobalShortcutAction,
        config: GlobalShortcutConfig,
    ): IO<Unit> =
        update(
            ioEffect {
                val raw = preference.first()
                val shown = decode(raw)
                val shownEntries =
                    GlobalShortcutAction.entries.associate {
                        GlobalShortcutsConfig.serialName(it) to json.encodeToJsonElement(shown[it])
                    }
                val stored = parse(raw).orEmpty()
                val edited = GlobalShortcutsConfig.serialName(action) to json.encodeToJsonElement(config)
                preference.setAndCommit(json.encodeToString(JsonObject(shownEntries + stored + edited))).bind()
            },
        )

    private fun parse(raw: String): JsonObject? =
        runCatchingNonFatal { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    // Coordinate the backing preference as well, so settings restore uses the same transaction.
    // A failed write may still have changed the stored value; only then roll it back,
    // preserving both errors if the rollback fails.
    @Suppress("TooGenericExceptionCaught")
    fun update(persist: IO<Unit>): IO<Unit> =
        ioEffect {
            withContext(NonCancellable) {
                mutex.withLock {
                    val previous = preference.first()
                    try {
                        persist.bind()
                    } catch (e: Throwable) {
                        e.throwIfFatalOrCancellation()
                        try {
                            if (preference.first() != previous) {
                                preference.setAndCommit(previous).bind()
                            }
                        } catch (rollback: Throwable) {
                            rollback.throwIfFatalOrCancellation()
                            throw GlobalShortcutPersistenceRollbackException(e, rollback)
                        }
                        throw e
                    }
                }
            }
        }
}
