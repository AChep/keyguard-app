package com.artemchep.keyguard.copy

import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.toIO
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.kodein.di.DirectDI

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DownloadRepositoryDesktop(
) : DownloadRepository {
    private val sink = MutableStateFlow(persistentListOf<DownloadInfoEntity2>())

    constructor(
        directDI: DirectDI,
    ) : this(
    )

    override fun getById(id: String): IO<DownloadInfoEntity2?> = sink
        .map { list ->
            list
                .firstOrNull { it.id == id }
        }
        .toIO()

    override fun getByIdFlow(id: String): Flow<DownloadInfoEntity2?> = sink
        .map { list ->
            list
                .firstOrNull { it.id == id }
        }

    override fun getByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ): IO<DownloadInfoEntity2?> = sink
        .map { list ->
            list
                .firstOrNull {
                    it.localCipherId == tag.localCipherId &&
                            it.remoteCipherId == tag.remoteCipherId &&
                            it.attachmentId == tag.attachmentId
                }
        }
        .toIO()

    override fun removeById(id: String): IO<Unit> = ioEffect {
        sink.update { list ->
            list
                .filter {
                    it.id != id
                }
                .toPersistentList()
        }
    }

    override fun removeByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ): IO<Unit> = ioEffect {
        sink.update { list ->
            list
                .filter {
                    it.localCipherId != tag.localCipherId ||
                            it.remoteCipherId != tag.remoteCipherId ||
                            it.attachmentId != tag.attachmentId
                }
                .toPersistentList()
        }
    }

    override fun get(): Flow<List<DownloadInfoEntity2>> = sink

    override fun put(model: DownloadInfoEntity2): IO<Unit> = ioEffect {
        sink.update { list ->
            list
                .filter {
                    it.id != model.id
                }
                .toPersistentList()
                .add(model)
        }
    }
}
