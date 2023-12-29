package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.AccountTask
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

interface Watchdog {
    fun <T> track(
        accountId: AccountId,
        accountTask: AccountTask,
        io: IO<T>,
    ) = track(
        accountIdSet = setOf(accountId),
        accountTask = accountTask,
        io = io,
    )

    fun <T> track(
        accountIdSet: Set<AccountId>,
        accountTask: AccountTask,
        io: IO<T>,
    ): IO<T>
}

interface SupervisorRead {
    fun get(): Flow<Map<AccountTask, Set<AccountId>>>

    fun get(accountTask: AccountTask): Flow<Set<AccountId>>
}

class WatchdogImpl() : Watchdog, SupervisorRead {
    private val sink = MutableStateFlow(
        value = persistentMapOf<AccountTask, PersistentMap<AccountId, Int>>(),
    )

    override fun <T> track(
        accountIdSet: Set<AccountId>,
        accountTask: AccountTask,
        io: IO<T>,
    ): IO<T> = ioEffect {
        try {
            updateState(accountIdSet, accountTask, Int::inc)
            io.bind()
        } finally {
            updateState(accountIdSet, accountTask, Int::dec)
        }
    }

    private fun updateState(
        keys: Set<AccountId>,
        section: AccountTask,
        block: (Int) -> Int,
    ) = sink.update { map ->
        val initialSection = map.getOrElse(section) { persistentMapOf() }
        val newSection = keys.fold(initialSection) { y, key ->
            val v = y
                .getOrElse(key) { 0 }
                .let(block)
            if (v > 0) {
                y.put(key, v)
            } else {
                y.remove(key)
            }
        }
        map.put(section, newSection)
    }

    override fun get(): Flow<Map<AccountTask, Set<AccountId>>> = sink
        .map { store ->
            store.mapValues { it.value.convertToSet() }
        }
        .distinctUntilChanged()

    private fun Map<AccountId, Int>.convertToSet() = this
        .asSequence()
        .mapNotNull { it.takeKeyIfExists() }
        .toSet()

    private fun Map.Entry<AccountId, Int>.takeKeyIfExists() = takeIf { it.value > 0 }?.key

    override fun get(accountTask: AccountTask): Flow<Set<AccountId>> = get()
        .map { all ->
            // Get the specific section of the data.
            all[accountTask].orEmpty()
        }
        .distinctUntilChanged()
}
