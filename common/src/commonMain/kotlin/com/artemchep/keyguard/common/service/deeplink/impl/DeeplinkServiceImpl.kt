package com.artemchep.keyguard.common.service.deeplink.impl

import com.artemchep.keyguard.common.service.deeplink.DeeplinkService
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.kodein.di.DirectDI

class DeeplinkServiceImpl(
) : DeeplinkService {
    constructor(directDI: DirectDI) : this(
    )

    private val sink = MutableStateFlow(persistentMapOf<String, String?>())

    override fun get(key: String): String? = sink.value[key]

    override fun getFlow(key: String): Flow<String?> = sink
        .map { state ->
            state[key]
        }

    override fun put(key: String, value: String?) {
        sink.update { state ->
            state.put(key, value)
        }
    }

    override fun clear(key: String) {
        sink.update { state ->
            state.remove(key)
        }
    }
}