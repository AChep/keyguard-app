package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.android.downloader.journal.room.DownloadDatabaseManager
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoDao
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.android.downloader.journal.room.toDomain
import com.artemchep.keyguard.android.downloader.journal.room.toEntity
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class DownloadRepositoryImpl(
    private val databaseManager: DownloadDatabaseManager,
) : DownloadRepository {
    constructor(
        directDI: DirectDI,
    ) : this(
        databaseManager = directDI.instance(),
    )

    override fun getById(id: String): IO<DownloadInfoEntity2?> =
        daoEffect { dao ->
            dao.getById(id = id)
                ?.toDomain()
        }

    override fun getByIdFlow(id: String): Flow<DownloadInfoEntity2?> =
        daoEffect { dao ->
            dao.getByIdFlow(id = id)
                .map { entities ->
                    entities.firstOrNull()
                        ?.toDomain()
                }
        }.asFlow().flatMapLatest { it }

    override fun getByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ): IO<DownloadInfoEntity2?> = daoEffect { dao ->
        dao.getByTag(
            localCipherId = tag.localCipherId,
            remoteCipherId = tag.remoteCipherId,
            attachmentId = tag.attachmentId,
        )?.toDomain()
    }

    override fun removeById(id: String): IO<Unit> = daoEffect { dao ->
        dao.removeById(id = id)
    }

    override fun removeByTag(
        tag: DownloadInfoEntity2.AttachmentDownloadTag,
    ): IO<Unit> = daoEffect { dao ->
        dao.removeByTag(
            localCipherId = tag.localCipherId,
            remoteCipherId = tag.remoteCipherId,
            attachmentId = tag.attachmentId,
        )
    }

    override fun get(): Flow<List<DownloadInfoEntity2>> =
        daoEffect { dao ->
            dao.getAll()
                .map { entities ->
                    entities.map { it.toDomain() }
                }
        }.asFlow().flatMapLatest { it }

    override fun put(model: DownloadInfoEntity2): IO<Unit> =
        daoEffect { dao ->
            dao.insertAll(model.toEntity())
        }

    private inline fun <T> daoEffect(
        crossinline block: suspend (DownloadInfoDao) -> T,
    ): IO<T> = databaseManager
        .get()
        .effectMap { db ->
            val dao = db.downloadInfoDao()
            block(dao)
        }
}
