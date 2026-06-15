package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.SyncManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class SyncPrimitivesTest {
    @Test
    fun `afterLocalPut runs after successful local put`() = runTest {
        val events = mutableListOf<String>()

        syncRemoteOverwrite(
            localPut = {
                events += "localPut"
            },
            remotePut = { local ->
                events += "remotePut"
                afterLocalPut {
                    events += "cleanup"
                }
                decodedItem(local)
            },
        )

        assertEquals(
            listOf("remotePut", "localPut", "cleanup"),
            events,
        )
    }

    @Test
    fun `afterLocalPut runs after failed remote put fallback is persisted`() = runTest {
        val events = mutableListOf<String>()
        val persisted = mutableListOf<DecodedItem>()

        syncRemoteOverwrite(
            localPut = { models ->
                events += "localPut"
                persisted += models
            },
            remotePut = { local ->
                events += "remotePut"
                val intermediate = decodedItem(local)
                updateLocalModel(intermediate)
                afterLocalPut {
                    events += "cleanup"
                }
                throw TestSyncException()
            },
        )

        assertEquals(
            listOf("remotePut", "localPut", "cleanup"),
            events,
        )
        assertEquals(1, persisted.size)
        assertEquals(
            BitwardenService.Error.CODE_UNKNOWN,
            persisted.single().service.error?.code,
        )
    }

    @Test
    fun `afterLocalPut does not run when local put fails`() = runTest {
        val events = mutableListOf<String>()

        syncRemoteOverwrite(
            localPut = {
                events += "localPut"
                throw TestSyncException()
            },
            remotePut = { local ->
                events += "remotePut"
                afterLocalPut {
                    events += "cleanup"
                }
                decodedItem(local)
            },
        )

        assertEquals(
            listOf("remotePut", "localPut"),
            events,
        )
    }

    @Test
    fun `local overwrite skips remote put retry`() = runTest {
        val events = mutableListOf<String>()

        syncRemoteOverwrite(
            localPut = {
                events += "localPut"
            },
            shouldOverwriteLocal = { _, _ -> true },
            shouldOverwriteRemote = { _, _ -> true },
            remotePut = { local ->
                events += "remotePut"
                decodedItem(local)
            },
        )

        assertEquals(
            listOf("localPut"),
            events,
        )
    }
}

private suspend fun syncRemoteOverwrite(
    localPut: suspend (List<DecodedItem>) -> Unit,
    shouldOverwriteLocal: (LocalItem, RemoteItem) -> Boolean = { _, _ -> false },
    shouldOverwriteRemote: (LocalItem, RemoteItem) -> Boolean = { _, _ -> true },
    remotePut: suspend RemotePutScope<RemoteItem, DecodedItem>.(LocalItem) -> DecodedItem,
) {
    val local = LocalItem(
        id = "local-1",
        revisionDate = TEST_INSTANT,
        service = BitwardenService(
            remote = BitwardenService.Remote(
                id = "remote-1",
                revisionDate = TEST_INSTANT,
                deletedDate = null,
            ),
            version = BitwardenService.VERSION,
        ),
    )
    val remote = RemoteItem(
        id = "remote-1",
        revisionDate = TEST_INSTANT,
    )

    syncX<LocalItem, LocalItem, RemoteItem, DecodedItem>(
        name = "test",
        localItems = listOf(local),
        localLens = SyncManager.LensLocal(
            getLocalId = { it.id },
            getLocalRevisionDate = { it.revisionDate },
        ),
        localReEncoder = ::decodedItem,
        localDecoder = { rawLocal, _ -> rawLocal },
        localDeleteById = {},
        localPut = localPut,
        shouldOverwriteLocal = shouldOverwriteLocal,
        shouldOverwriteRemote = shouldOverwriteRemote,
        remoteItems = listOf(remote),
        remoteLens = SyncManager.Lens(
            getId = { it.id },
            getRevisionDate = { it.revisionDate },
        ),
        remoteDecoder = { remoteItem, localItem ->
            decodedItem(
                id = localItem?.id ?: remoteItem.id,
                revisionDate = remoteItem.revisionDate,
                service = BitwardenService(
                    remote = BitwardenService.Remote(
                        id = remoteItem.id,
                        revisionDate = remoteItem.revisionDate,
                        deletedDate = null,
                    ),
                    version = BitwardenService.VERSION,
                ),
            )
        },
        remoteDecodedFallback = { _, localItem, _ ->
            decodedItem(localItem ?: local)
        },
        remoteDeleteById = {},
        remotePut = remotePut,
        onLog = { _: String, _: LogLevel -> },
    )
}

private data class LocalItem(
    val id: String,
    val revisionDate: Instant,
    override val service: BitwardenService,
) : BitwardenService.Has<LocalItem> {
    override fun withService(service: BitwardenService): LocalItem = copy(
        service = service,
    )
}

private data class RemoteItem(
    val id: String,
    val revisionDate: Instant,
)

private data class DecodedItem(
    val id: String,
    val revisionDate: Instant,
    override val service: BitwardenService,
) : BitwardenService.Has<DecodedItem> {
    override fun withService(service: BitwardenService): DecodedItem = copy(
        service = service,
    )
}

private fun decodedItem(
    local: LocalItem,
) = decodedItem(
    id = local.id,
    revisionDate = local.revisionDate,
    service = local.service,
)

private fun decodedItem(
    id: String,
    revisionDate: Instant,
    service: BitwardenService,
) = DecodedItem(
    id = id,
    revisionDate = revisionDate,
    service = service,
)

private class TestSyncException : RuntimeException()

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
