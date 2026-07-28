package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.toIO
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class DownloadRepositoryInMemory : DownloadRepository {
    private val sink = MutableStateFlow(persistentListOf<DownloadInfoEntity>())

    override fun get(): Flow<List<DownloadInfoEntity>> = sink

    override fun put(model: DownloadInfoEntity): IO<Unit> = ioEffect {
        sink.update { list ->
            list
                .filter { it.id != model.id }
                .toPersistentList()
                .add(model)
        }
    }

    override fun getById(id: String): IO<DownloadInfoEntity?> = sink
        .map { list -> list.firstOrNull { it.id == id } }
        .toIO()

    override fun getByIdFlow(id: String): Flow<DownloadInfoEntity?> = sink
        .map { list -> list.firstOrNull { it.id == id } }

    override fun getByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): IO<DownloadInfoEntity?> = sink
        .map { list -> list.firstOrNull { it.hasTag(tag) } }
        .toIO()

    override fun getByTagFlow(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): Flow<DownloadInfoEntity?> = sink
        .map { list -> list.firstOrNull { it.hasTag(tag) } }

    override fun removeById(id: String): IO<Unit> = ioEffect {
        sink.update { list ->
            list
                .filter { it.id != id }
                .toPersistentList()
        }
    }

    override fun removeByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): IO<Unit> = ioEffect {
        sink.update { list ->
            list
                .filterNot { it.hasTag(tag) }
                .toPersistentList()
        }
    }

    private fun DownloadInfoEntity.hasTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): Boolean =
        localCipherId == tag.localCipherId &&
            remoteCipherId == tag.remoteCipherId &&
            attachmentId == tag.attachmentId
}
