package com.artemchep.keyguard.common.service.state

import com.artemchep.keyguard.common.io.IO
import kotlinx.coroutines.flow.Flow

interface StateRepository {
    fun put(
        key: String,
        model: Map<String, Any?>,
    ): IO<Unit>

    fun get(key: String): Flow<Map<String, Any?>>
}
