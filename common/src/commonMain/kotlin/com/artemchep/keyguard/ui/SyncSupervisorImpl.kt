package com.artemchep.keyguard.ui

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class SyncSupervisorImpl<Key> {
    private val sink = MutableStateFlow(
        value = persistentMapOf<Key, Int>(),
    )

    val output = sink
        .map { it.keys }
        .distinctUntilChanged()

    fun <T> track(key: Key, io: IO<T>): IO<T> = ioEffect {
        try {
            updateState(key, Int::inc)
            io.bind()
        } finally {
            updateState(key, Int::dec)
        }
    }

    private fun updateState(key: Key, block: (Int) -> Int) =
        sink.update { map ->
            val v = map.getOrElse(key) { 0 }.let(block)
            if (v > 0) {
                map.put(key, v)
            } else {
                map.remove(key)
            }
        }
}
