package com.artemchep.keyguard.common.service.logging.inmemory

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Log
import com.artemchep.keyguard.common.service.logging.LogLevel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import org.kodein.di.DirectDI

class InMemoryLogRepositoryImpl(
) : InMemoryLogRepository {
    companion object {
        private const val DEFAULT_ENABLED = false
    }

    private val switchSink = MutableStateFlow(DEFAULT_ENABLED)

    private val logsSink = MutableStateFlow(persistentListOf<Log>())

    override val isEnabled: Boolean get() = switchSink.value

    constructor(
        directDI: DirectDI,
    ) : this()

    override fun setEnabled(enabled: Boolean): IO<Unit> = ioEffect {
        switchSink.value = enabled
        if (!enabled) {
            logsSink.value = persistentListOf()
        }
    }

    override fun getEnabled(): Flow<Boolean> = switchSink

    override fun get(): Flow<ImmutableList<Log>> = logsSink

    override suspend fun add(
        tag: String,
        message: String,
        level: LogLevel,
    ) {
        if (!isEnabled) {
            return
        }

        val now = Clock.System.now()
        val entity = Log(
            tag = tag,
            message = message,
            level = level,
            createdAt = now,
        )
        logsSink.update { logs ->
            logs.add(entity)
        }
    }
}