package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.attachments.AttachmentsState
import com.artemchep.keyguard.feature.attachments.attachmentsScreenStateProducer
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.VaultActionSnapshot
import com.artemchep.keyguard.apple.model.buildMenuActionSnapshots
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import org.koin.core.scope.Scope

data class DownloadsSnapshot(
    val loaded: Boolean,
    val items: List<DownloadItemSnapshot>,
) {
    companion object {
        val empty = DownloadsSnapshot(false, emptyList())
    }
}

data class DownloadItemSnapshot(
    val isSection: Boolean,
    val id: String,
    val title: String,
    val size: String?,
    /** One of NONE / LOADING / FAILED / DOWNLOADED / PENDING. */
    val statusKind: String,
    val downloaded: Long?,
    val total: Long?,
    val localUrl: String?,
    /** Routed by id via `invokeEntryAction`. */
    val actions: List<VaultActionSnapshot>,
)

/**
 * The per-row status / actions are persisted [kotlinx.coroutines.flow.StateFlow]s that
 * are frozen headless, so the snapshot reads `.value` at projection time — progress
 * refreshes when the outer list re-emits, not on every byte.
 */
internal class DownloadsController(
    private val ctx: CoreContext,
) {
    suspend fun produceDownloadsInto(
        scope: CoroutineScope,
        sessionKoin: Scope,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (DownloadsSnapshot, Map<String, () -> Unit>) -> Unit,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        val producerFlow = with(sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("attachments", scope, interceptor)
                .attachmentsScreenStateProducer(
                    filterContext = get(),
                    addCipherFilter = get(),
                    confirmationRouteFactory = get(),
                    getCipherFilters = get(),
                    getAccounts = get(),
                    getProfiles = get(),
                    getCiphers = get(),
                    getFolders = get(),
                    getTags = get(),
                    getCollections = get(),
                    getOrganizations = get(),
                    downloadRepository = get(),
                    downloadManager = get(),
                    downloadAttachment = get(),
                    removeAttachment = get(),
                    canPreviewAttachment = get(),
                    attachmentPreviewRouteFactory = get(),
                    vaultViewRouteFactory = get(),
                )
        }
        producerFlow
            .map { loadable ->
                val state = loadable.getOrNull()
                val handlers = LinkedHashMap<String, () -> Unit>()
                val items = ArrayList<DownloadItemSnapshot>()
                for (item in state?.items.orEmpty()) {
                    when (item) {
                        is AttachmentsState.Item.Section ->
                            items += DownloadItemSnapshot(
                                isSection = true,
                                id = item.key,
                                title = item.name,
                                size = null,
                                statusKind = "NONE",
                                downloaded = null,
                                total = null,
                                localUrl = null,
                                actions = emptyList(),
                            )

                        is AttachmentsState.Item.Attachment -> {
                            val attachment = item.item
                            val status = attachment.statusState.value
                            val statusKind = when (status) {
                                is AttachmentItem.Status.None -> "NONE"
                                is AttachmentItem.Status.Loading -> "LOADING"
                                is AttachmentItem.Status.Failed -> "FAILED"
                                is AttachmentItem.Status.Downloaded -> "DOWNLOADED"
                                is AttachmentItem.Status.PendingUpload -> "PENDING"
                            }
                            val loading = status as? AttachmentItem.Status.Loading
                            val downloaded = status as? AttachmentItem.Status.Downloaded
                            val actions = buildMenuActionSnapshots(
                                actions = attachment.actionsState.value,
                                idPrefix = item.key,
                                leContext = leContext,
                                handlers = handlers,
                            )
                            items += DownloadItemSnapshot(
                                isSection = false,
                                id = item.key,
                                title = attachment.name,
                                size = attachment.size,
                                statusKind = statusKind,
                                downloaded = loading?.downloaded,
                                total = loading?.total,
                                localUrl = downloaded?.localUrl,
                                actions = actions,
                            )
                        }
                    }
                }
                DownloadsSnapshot(loaded = state != null, items = items) to
                    (handlers as Map<String, () -> Unit>)
            }
            .collectOnMain { (snapshot, handlers) -> publish(snapshot, handlers) }
    }
}
