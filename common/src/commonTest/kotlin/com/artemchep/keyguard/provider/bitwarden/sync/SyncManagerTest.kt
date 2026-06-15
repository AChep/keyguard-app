package com.artemchep.keyguard.provider.bitwarden.sync

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncManagerTest {
    @Test
    fun `retryable error does not block forced remote overwrite at matching revisions`() {
        val local = LocalItem(
            localId = "local-1",
            revisionDate = TEST_INSTANT,
            service = BitwardenService(
                remote = BitwardenService.Remote(
                    id = "remote-1",
                    revisionDate = TEST_INSTANT,
                    deletedDate = null,
                ),
                error = BitwardenService.Error(
                    code = BitwardenService.Error.CODE_UNKNOWN,
                    revisionDate = TEST_INSTANT,
                ),
                version = BitwardenService.VERSION,
            ),
            pendingRemoteOverwrite = true,
        )
        val remote = RemoteItem(
            remoteId = "remote-1",
            revisionDate = TEST_INSTANT,
        )

        val df = syncManager.df(
            localItems = listOf(local),
            remoteItems = listOf(remote),
            shouldOverwriteLocal = { _, _ -> false },
            shouldOverwriteRemote = { candidate, _ -> candidate.pendingRemoteOverwrite },
        )

        assertEquals(emptyList(), df.localPutCipher)
        assertEquals(1, df.remotePutCipher.size)
        assertTrue(df.remotePutCipher.single().force)
    }
}

private data class LocalItem(
    val localId: String,
    val revisionDate: Instant,
    override val service: BitwardenService,
    val pendingRemoteOverwrite: Boolean,
) : BitwardenService.Has<LocalItem> {
    override fun withService(service: BitwardenService): LocalItem = copy(
        service = service,
    )
}

private data class RemoteItem(
    val remoteId: String,
    val revisionDate: Instant,
)

private val syncManager = SyncManager(
    local = SyncManager.LensLocal<LocalItem>(
        getLocalId = { it.localId },
        getLocalRevisionDate = { it.revisionDate },
    ),
    remote = SyncManager.Lens<RemoteItem>(
        getId = { it.remoteId },
        getRevisionDate = { it.revisionDate },
    ),
)

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")
