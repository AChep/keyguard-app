package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetTags
import com.artemchep.keyguard.common.usecase.GetVaultSearchIndex
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.withLogTimeOfFirstEvent
import com.artemchep.keyguard.feature.home.vault.search.engine.SurfaceAwareVaultSearchIndex
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndex
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndexBuilder
import com.artemchep.keyguard.feature.home.vault.search.engine.VaultSearchIndexMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

class GetVaultSearchIndexImpl(
    private val logRepository: LogRepository,
    private val getCiphers: GetCiphers,
    private val getAccounts: GetAccounts,
    private val getFolders: GetFolders,
    private val getTags: GetTags,
    private val getCollections: GetCollections,
    private val getOrganizations: GetOrganizations,
    private val searchIndexBuilder: VaultSearchIndexBuilder,
    private val windowCoroutineScope: WindowCoroutineScope,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetVaultSearchIndex {
    companion object {
        private const val TAG = "GetVaultSearchIndex"
    }

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
        getCiphers = directDI.instance(),
        getAccounts = directDI.instance(),
        getFolders = directDI.instance(),
        getTags = directDI.instance(),
        getCollections = directDI.instance(),
        getOrganizations = directDI.instance(),
        searchIndexBuilder = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val metadataFlow = combine(
        getAccounts(),
        getFolders(),
        getTags(),
        getCollections(),
        getOrganizations(),
    ) { accounts, folders, tags, collections, organizations ->
        VaultSearchIndexMetadata(
            accounts = accounts,
            folders = folders,
            tags = tags,
            collections = collections,
            organizations = organizations,
        )
    }

    private val sharedFlow = combine(
        getCiphers(),
        metadataFlow,
    ) { ciphers, metadata ->
        ciphers to metadata
    }
        .mapLatest { (ciphers, metadata) ->
            searchIndexBuilder.build(
                items = ciphers,
                metadata = metadata,
            )
        }
        .withLogTimeOfFirstEvent(logRepository, TAG)
        .flowOn(dispatcher)
        .shareIn(
            windowCoroutineScope,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 5000L,
                replayExpirationMillis = 0L,
            ),
            replay = 1,
        )

    override fun invoke(
        surface: String?,
    ): Flow<VaultSearchIndex> = sharedFlow
        .map { searchIndex ->
            (searchIndex as? SurfaceAwareVaultSearchIndex)
                ?.withSurface(surface)
                ?: searchIndex
        }
}
