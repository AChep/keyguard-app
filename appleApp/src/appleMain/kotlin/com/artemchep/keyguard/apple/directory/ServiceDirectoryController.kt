package com.artemchep.keyguard.apple.directory

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeService
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataService
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.apple.core.EntryListQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** Factories and domain loading only. Mutable screen state belongs to each list session. */
internal class ServiceDirectoryController(private val ctx: CoreContext) {
    fun createListSession(
        scope: CoroutineScope,
        kind: String,
        query: MutableStateFlow<EntryListQuery>,
        onOpen: (String) -> Unit,
        onChange: (ServiceDirectorySnapshot) -> Unit,
    ) = ServiceDirectoryListSession(scope, query, { loadEntries(kind) }, onOpen, onChange)

    // Catalog failures become a terminal error snapshot; cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    fun observeServiceDirectoryDetail(
        kind: String,
        itemId: String,
        onChange: (ServiceDirectoryDetailSnapshot) -> Unit,
    ): KeyguardCancellable = ctx.launchObserver {
        flow {
            emit(ServiceDirectoryDetailSnapshot.empty)
            try {
                val items = directoryRows(loadEntries(kind))
                val detail = items.firstOrNull { it.id == itemId }?.entry?.detail
                emit(detail ?: ServiceDirectoryDetailSnapshot.empty.copy(loaded = true, notFound = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordException(e)
                emit(ServiceDirectoryDetailSnapshot.empty.copy(loaded = true, failed = true))
            }
        }.collectOnMain(onChange)
    }

    private suspend fun loadEntries(kind: String): List<DirectoryEntry> = with(ctx.koin) {
        when (kind) {
            DIRECTORY_KIND_PASSKEYS -> get<GetPasskeys>()()().map {
                DirectoryEntry(it.id, it.name, it.documentation, it.toServiceDirectoryDetailSnapshot())
            }
            DIRECTORY_KIND_TWO_FA -> get<GetTwoFa>()()().map {
                DirectoryEntry(it.name, it.name, it.documentation, it.toServiceDirectoryDetailSnapshot())
            }
            DIRECTORY_KIND_GET_MY_DATA -> get<JustGetMyDataService>().get()().map {
                DirectoryEntry(it.name, it.name, it.url, it.toServiceDirectoryDetailSnapshot())
            }
            DIRECTORY_KIND_DELETE_ACCOUNT -> get<JustDeleteMeService>().get()().map {
                DirectoryEntry(it.name, it.name, it.url, it.toServiceDirectoryDetailSnapshot())
            }
            else -> error("Unknown directory kind: $kind")
        }
    }
}

internal data class DirectoryEntry(
    val key: String,
    val name: String,
    val faviconUrl: String?,
    val detail: ServiceDirectoryDetailSnapshot,
)
