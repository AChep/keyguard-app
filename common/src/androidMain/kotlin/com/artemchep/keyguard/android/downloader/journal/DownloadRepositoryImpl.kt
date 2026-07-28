package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.android.downloader.journal.room.DownloadDatabaseManager
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoDao
import com.artemchep.keyguard.android.downloader.journal.room.toDomain
import com.artemchep.keyguard.android.downloader.journal.room.toEntity
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.download.DownloadRepository
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

    override fun getById(id: String): IO<DownloadInfoEntity?> =
        daoEffect { dao ->
            dao.getById(id = id)
                ?.toDomain()
        }

    override fun getByIdFlow(id: String): Flow<DownloadInfoEntity?> =
        daoEffect { dao ->
            dao.getByIdFlow(id = id)
                .map { entities ->
                    entities.firstOrNull()
                        ?.toDomain()
                }
        }.asFlow().flatMapLatest { it }

    override fun getByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): IO<DownloadInfoEntity?> = daoEffect { dao ->
        dao.getByTag(
            localCipherId = tag.localCipherId,
            remoteCipherId = tag.remoteCipherId,
            attachmentId = tag.attachmentId,
        )?.toDomain()
    }

    override fun getByTagFlow(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): Flow<DownloadInfoEntity?> =
        daoEffect { dao ->
            dao.getByTagFlow(
                localCipherId = tag.localCipherId,
                remoteCipherId = tag.remoteCipherId,
                attachmentId = tag.attachmentId,
            )
                .map { entities ->
                    entities.firstOrNull()
                        ?.toDomain()
                }
        }.asFlow().flatMapLatest { it }

    override fun removeById(id: String): IO<Unit> = daoEffect { dao ->
        dao.removeById(id = id)
    }

    override fun removeByTag(
        tag: DownloadInfoEntity.AttachmentDownloadTag,
    ): IO<Unit> = daoEffect { dao ->
        dao.removeByTag(
            localCipherId = tag.localCipherId,
            remoteCipherId = tag.remoteCipherId,
            attachmentId = tag.attachmentId,
        )
    }

    override fun get(): Flow<List<DownloadInfoEntity>> =
        daoEffect { dao ->
            dao.getAll()
                .map { entities ->
                    entities.map { it.toDomain() }
                }
        }.asFlow().flatMapLatest { it }

    override fun put(model: DownloadInfoEntity): IO<Unit> =
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
