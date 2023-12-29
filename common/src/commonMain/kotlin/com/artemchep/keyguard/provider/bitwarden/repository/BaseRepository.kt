package com.artemchep.keyguard.provider.bitwarden.repository

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.toIO
import kotlinx.coroutines.flow.Flow

interface BaseRepository<T> {
    fun get(): Flow<List<T>>

    /**
     * Returns the first state of the
     * data.
     */
    fun getSnapshot(): IO<List<T>> = get().toIO()

    fun put(model: T): IO<Unit>
}
