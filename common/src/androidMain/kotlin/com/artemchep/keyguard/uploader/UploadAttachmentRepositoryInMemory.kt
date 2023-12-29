package com.artemchep.keyguard.android.uploader

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kodein.di.DirectDI

private typealias State = PersistentMap<String, UploadAttachmentRequest>

class UploadAttachmentRepositoryInMemory() : UploadAttachmentRepository {
    private val mutex = Mutex()

    private val sink: MutableStateFlow<State> = MutableStateFlow(persistentMapOf())

    constructor(
        directDI: DirectDI,
    ) : this()

    override suspend fun add(
        request: UploadAttachmentRequest,
    ) = modify { state ->
        state.put(request.requestId, request)
    }

    override suspend fun remove(
        requestId: String,
    ) = modify { state ->
        state.remove(requestId)
    }

    private suspend fun modify(
        block: (State) -> State,
    ) = mutex.withLock {
        sink.update(block)
    }

    override fun getAll(): Flow<ImmutableList<UploadAttachmentRequest>> = sink
        .map { state ->
            state.values.toImmutableList()
        }
}
