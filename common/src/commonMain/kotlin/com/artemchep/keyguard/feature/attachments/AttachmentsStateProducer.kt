package com.artemchep.keyguard.feature.attachments

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.runtime.Composable
import arrow.core.identity
import arrow.core.partially1
import com.artemchep.keyguard.android.downloader.journal.DownloadRepository
import com.artemchep.keyguard.android.downloader.journal.room.DownloadInfoEntity2
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DownloadAttachmentRequest
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.RemoveAttachmentRequest
import com.artemchep.keyguard.common.service.download.DownloadManager
import com.artemchep.keyguard.common.usecase.DownloadAttachment
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetCollections
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetOrganizations
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.RemoveAttachment
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.common.util.flow.foldAsList
import com.artemchep.keyguard.feature.attachments.model.AttachmentItem
import com.artemchep.keyguard.feature.attachments.util.createAttachmentItem
import com.artemchep.keyguard.feature.decorator.ItemDecorator
import com.artemchep.keyguard.feature.decorator.ItemDecoratorNone
import com.artemchep.keyguard.feature.decorator.ItemDecoratorTitle
import com.artemchep.keyguard.feature.home.vault.screen.FilterParams
import com.artemchep.keyguard.feature.home.vault.screen.OurFilterResult
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewRoute
import com.artemchep.keyguard.feature.home.vault.screen.ah
import com.artemchep.keyguard.feature.home.vault.screen.createFilter
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.home.vault.util.AlphabeticalSortMinItemsSize
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import com.artemchep.keyguard.ui.selection.selectionHandle
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceAttachmentsScreenState() = with(localDI().direct) {
    produceAttachmentsScreenState(
        directDI = this,
        getAccounts = instance(),
        getProfiles = instance(),
        getCiphers = instance(),
        getFolders = instance(),
        getCollections = instance(),
        getOrganizations = instance(),
        downloadRepository = instance(),
        downloadManager = instance(),
        downloadAttachment = instance(),
        removeAttachment = instance(),
    )
}

private data class CipherWithAttachment(
    val cipher: DSecret,
    val attachment: DSecret.Attachment.Remote,
)

private data class ItemCipher(
    val item: AttachmentItem,
    val cipher: DSecret,
    val attachment: DSecret.Attachment.Remote,
)

private data class FilteredBoo<T>(
    val count: Int,
    val list: List<T>,
    val filterConfig: FilterHolder? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun produceAttachmentsScreenState(
    directDI: DirectDI,
    getAccounts: GetAccounts,
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    getCollections: GetCollections,
    getOrganizations: GetOrganizations,
    downloadRepository: DownloadRepository,
    downloadManager: DownloadManager,
    downloadAttachment: DownloadAttachment,
    removeAttachment: RemoveAttachment,
): Loadable<AttachmentsState> = produceScreenState(
    key = "attachments",
    args = arrayOf(
        getCiphers,
    ),
    initial = Loadable.Loading,
) {
    val selectionHandle = selectionHandle("selection")

    val filterResult = createFilter()

    val ciphersFlow = getCiphers()

    val ciphersByAttachmentRefFlow = ciphersFlow
        .map { ciphers ->
            ciphers
                .asSequence()
                .flatMap { cipher ->
                    cipher
                        .attachments
                        .asSequence()
                        .mapNotNull { it as? DSecret.Attachment.Remote }
                        .map { attachment ->
                            val model = CipherWithAttachment(
                                cipher = cipher,
                                attachment = attachment,
                            )
                            val tag = DownloadInfoEntity2.AttachmentDownloadTag(
                                localCipherId = cipher.id,
                                remoteCipherId = cipher.service.remote?.id,
                                attachmentId = attachment.id,
                            )
                            tag to model
                        }
                }
                .associate { it }
        }
        .shareInScreenScope()

    val itemsRawFlow = downloadRepository
        // get a list of all known tags
        .get()
        .map {
            it
                .asSequence()
                .map {
                    DownloadInfoEntity2.AttachmentDownloadTag(
                        localCipherId = it.localCipherId,
                        remoteCipherId = it.remoteCipherId,
                        attachmentId = it.attachmentId,
                    )
                }
                .toSet()
        }
        .distinctUntilChanged()
        // filter it by being available offline
        .flatMapLatest { refs ->
            refs
                .map { ref ->
                    ciphersByAttachmentRefFlow
                        .map { state -> state[ref] }
                        .distinctUntilChanged()
                        .flatMapLatest { cipherWithAttachment ->
                            cipherWithAttachment
                                ?: return@flatMapLatest flowOf(null)

                            val attachment = cipherWithAttachment.attachment
                            val cipher = cipherWithAttachment.cipher

                            val downloadIo = kotlin.run {
                                val request = DownloadAttachmentRequest.ByLocalCipherAttachment(
                                    cipher = cipher,
                                    attachment = attachment,
                                )
                                downloadAttachment(listOf(request))
                            }
                            val removeIo = kotlin.run {
                                val request = RemoveAttachmentRequest.ByLocalCipherAttachment(
                                    cipher = cipher,
                                    attachment = attachment,
                                )
                                removeAttachment(listOf(request))
                            }
                            flow<ItemCipher> {
                                coroutineScope {
                                    val actualItem = createAttachmentItem(
                                        selectionHandle = selectionHandle,
                                        tag = ref,
                                        sharingScope = this,
                                        attachment = attachment,
                                        launchViewCipherData = LaunchViewCipherData(
                                            accountId = cipher.accountId,
                                            cipherId = cipher.id,
                                        ),
                                        downloadManager = downloadManager,
                                        downloadIo = downloadIo,
                                        removeIo = removeIo,
                                    )
                                    val wrapperItem = ItemCipher(
                                        item = actualItem,
                                        cipher = cipher,
                                        attachment = attachment,
                                    )
                                    emit(wrapperItem)
                                    awaitCancellation()
                                }
                            }
                        }
                }
                .foldAsList()
                .map { list ->
                    list
                        .filterNotNull()
                        .sortedWith(StringComparatorIgnoreCase { it.item.name })
                }
        }
        .shareInScreenScope()

    // Automatically de-select items
    // that do not exist.
    combine(
        itemsRawFlow,
        selectionHandle.idsFlow,
    ) { attachments, selectedAttachmentIds ->
        val newSelectedAttachmentIds = selectedAttachmentIds
            .asSequence()
            .filter { attachmentId ->
                attachments.any { it.attachment.id == attachmentId }
            }
            .toSet()
        newSelectedAttachmentIds.takeIf { it.size < selectedAttachmentIds.size }
    }
        .filterNotNull()
        .onEach { ids -> selectionHandle.setSelection(ids) }
        .launchIn(screenScope)

    val selectionFlow = combine(
        itemsRawFlow,
        selectionHandle.idsFlow,
    ) { items, selectedCipherIds ->
        val selectedItems = items
            .filter { it.attachment.id in selectedCipherIds }
        items to selectedItems
    }
        .flatMapLatest { (allItems, selectedItems) ->
            selectedItems
                .map { item ->
                    item.item.statusState
                        .map { attachmentStatus ->
                            item to FooStatus.of(
                                attachmentStatus = attachmentStatus,
                            )
                        }
                        .distinctUntilChanged()
                }
                .foldAsList()
                .map { allItems to it }
        }
        .map { (allItems, selectedPairs) ->
            if (selectedPairs.isEmpty()) {
                return@map null
            }

            val selectedItemsAllDownloaded = selectedPairs
                .all { it.second is FooStatus.Downloaded }
            val selectedItemsAllCancelable = selectedPairs
                .all {
                    val status = it.second
                    status is FooStatus.Loading ||
                            status is FooStatus.Failed
                }
            val selectedItemsAllDownloadable = selectedPairs
                .all {
                    val status = it.second
                    status is FooStatus.None ||
                            status is FooStatus.Failed
                }

            val downloadIo by lazy {
                val requests = selectedPairs
                    .map { (i, _) ->
                        DownloadAttachmentRequest.ByLocalCipherAttachment(
                            cipher = i.cipher,
                            attachment = i.attachment,
                        )
                    }
                downloadAttachment(requests)
            }
            val removeIo by lazy {
                val requests = selectedPairs
                    .map { (i, _) ->
                        RemoveAttachmentRequest.ByLocalCipherAttachment(
                            cipher = i.cipher,
                            attachment = i.attachment,
                        )
                    }
                removeAttachment(requests)
            }

            val actions = mutableListOf<FlatItemAction>()
            if (selectedItemsAllCancelable) {
                actions += FlatItemAction(
                    leading = icon(Icons.Outlined.Cancel),
                    title = translate(Res.strings.cancel),
                    onClick = {
                        removeIo.launchIn(appScope)
                    },
                )
            }
            if (selectedItemsAllDownloadable) {
                actions += FlatItemAction(
                    leading = icon(Icons.Outlined.Download),
                    title = "Download",
                    onClick = {
                        downloadIo.launchIn(appScope)
                    },
                )
            }
            if (selectedItemsAllDownloaded) {
                actions += FlatItemAction(
                    leading = icon(Icons.Outlined.Delete),
                    title = "Delete local files",
                    onClick = {
                        removeIo.launchIn(appScope)
                    },
                )
            }

            Selection(
                count = selectedPairs.size,
                actions = actions.toPersistentList(),
                onSelectAll = if (selectedPairs.size < allItems.size) {
                    val allIds = allItems
                        .asSequence()
                        .map { it.attachment.id }
                        .toSet()
                    selectionHandle::setSelection
                        .partially1(allIds)
                } else {
                    null
                },
                onClear = selectionHandle::clearSelection,
            )
        }

    val itemsFilteredFlow = itemsRawFlow
        .map { attachments ->
            FilteredBoo(
                count = attachments.size,
                list = attachments,
            )
        }
        .combine(
            flow = filterResult.filterFlow,
        ) { state, filterConfig ->
            // Fast path: if the there are no filters, then
            // just return original list of items.
            if (filterConfig.state.isEmpty()) {
                return@combine state.copy(
                    filterConfig = filterConfig,
                )
            }

            val filteredAllItems = state
                .list
                .run {
                    val ciphers = map { it.cipher }
                    val predicate = filterConfig.filter.prepare(directDI, ciphers)
                    filter { predicate(it.cipher) }
                }
            state.copy(
                list = filteredAllItems,
                filterConfig = filterConfig,
            )
        }
        .shareInScreenScope()

    val filterListFlow = ah(
        directDI = directDI,
        outputGetter = { it.cipher },
        outputFlow = itemsFilteredFlow
            .map { it.list },
        accountGetter = ::identity,
        accountFlow = getAccounts(),
        profileFlow = getProfiles(),
        cipherGetter = ::identity,
        cipherFlow = ciphersFlow,
        folderGetter = ::identity,
        folderFlow = getFolders(),
        collectionGetter = ::identity,
        collectionFlow = getCollections(),
        organizationGetter = ::identity,
        organizationFlow = getOrganizations(),
        input = filterResult,
        params = FilterParams(
            section = FilterParams.Section(
                type = false,
                misc = false,
            ),
        ),
    )
        .stateIn(screenScope, SharingStarted.WhileSubscribed(), OurFilterResult())

    val itemsFlow = itemsFilteredFlow
        .map { state ->
            val decorator: ItemDecorator<AttachmentsState.Item, AttachmentItem> =
                when {
                    state.list.size >= AlphabeticalSortMinItemsSize ->
                        ItemDecoratorTitle(
                            selector = { it.name },
                            factory = { id, text ->
                                AttachmentsState.Item.Section(
                                    key = id,
                                    name = text,
                                )
                            },
                        )

                    else ->
                        ItemDecoratorNone
                                as ItemDecorator<AttachmentsState.Item, AttachmentItem>
                }

            val items = sequence<AttachmentsState.Item> {
                state.list.forEach { item ->
                    val section = decorator.getOrNull(item.item)
                    if (section != null) yield(section)

                    val wrappedItem = AttachmentsState.Item.Attachment(
                        key = item.item.key,
                        item = item.item,
                    )
                    yield(wrappedItem)
                }
            }.toList()
            FilteredBoo(
                count = state.list.size,
                list = items,
                filterConfig = state.filterConfig,
            )
        }
        .map {
            it.list
        }

    val state = combine(
        itemsFlow,
        selectionFlow,
        filterListFlow,
    ) { items, selection, filterState ->
        val state = AttachmentsState(
            filter = AttachmentsState.Filter(
                items = filterState.items,
                onClear = filterState.onClear,
            ),
            stats = AttachmentsState.Stats(
                totalAttachments = 0,
                totalSize = "12",
            ),
            selection = selection,
            items = items,
        )
        Loadable.Ok(state)
    }
    state
}

private fun itemKeyForAttachment(key: String) = "attachment.$key"

sealed interface FooStatus {
    companion object {
        fun of(attachmentStatus: AttachmentItem.Status) = when (attachmentStatus) {
            is AttachmentItem.Status.None -> None
            is AttachmentItem.Status.Loading -> Loading
            is AttachmentItem.Status.Failed -> Failed
            is AttachmentItem.Status.Downloaded ->
                Downloaded(
                    localUrl = attachmentStatus.localUrl,
                )
        }
    }

    data object None : FooStatus

    data object Loading : FooStatus

    data object Failed : FooStatus

    data class Downloaded(
        val localUrl: String,
    ) : FooStatus
}

data class LaunchViewCipherData(
    val accountId: String,
    val cipherId: String,
)

fun foo(
    translatorScope: TranslatorScope,
    fileName: String,
    status: FooStatus,
    launchViewCipherData: LaunchViewCipherData?,
    downloadIo: IO<Unit>,
    removeIo: IO<Unit>,
    navigate: (NavigationIntent) -> Unit,
): List<ContextItem> {
    fun performDownload() {
        downloadIo
            .attempt()
            .launchIn(GlobalScope)
    }

    fun performRemove() {
        removeIo
            .attempt()
            .launchIn(GlobalScope)
    }

    return buildContextItems {
        section {
            when (status) {
                is FooStatus.None -> {
                    this += FlatItemAction(
                        icon = Icons.Outlined.Download,
                        title = translatorScope.translate(Res.strings.download),
                        onClick = ::performDownload,
                        type = FlatItemAction.Type.DOWNLOAD,
                    )
                }

                is FooStatus.Loading -> {
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Cancel),
                        title = translatorScope.translate(Res.strings.cancel),
                        onClick = ::performRemove,
                    )
                }

                is FooStatus.Failed -> {
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Cancel),
                        title = translatorScope.translate(Res.strings.cancel),
                        onClick = ::performRemove,
                    )
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Refresh),
                        title = translatorScope.translate(Res.strings.download),
                        onClick = ::performDownload,
                        type = FlatItemAction.Type.DOWNLOAD,
                    )
                }

                is FooStatus.Downloaded -> {
                    val fileUrl = status.localUrl
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.FileOpen),
                        trailing = {
                            ChevronIcon()
                        },
                        title = translatorScope.translate(Res.strings.file_action_open_with_title),
                        onClick = {
                            val intent = NavigationIntent.NavigateToPreview(
                                uri = fileUrl,
                                fileName = fileName,
                            )
                            navigate(intent)
                        },
                        type = FlatItemAction.Type.VIEW,
                    )
                    if (CurrentPlatform is Platform.Desktop) {
                        this += FlatItemAction(
                            leading = iconSmall(Icons.Outlined.FileOpen, Icons.Outlined.FolderOpen),
                            trailing = {
                                ChevronIcon()
                            },
                            title = translatorScope.translate(Res.strings.file_action_open_in_file_manager_title),
                            onClick = {
                                val intent = NavigationIntent.NavigateToPreviewInFileManager(
                                    uri = fileUrl,
                                    fileName = fileName,
                                )
                                navigate(intent)
                            },
                        )
                    }
                    if (CurrentPlatform is Platform.Mobile) {
                        this += FlatItemAction(
                            leading = icon(Icons.Outlined.Send),
                            trailing = {
                                ChevronIcon()
                            },
                            title = translatorScope.translate(Res.strings.file_action_send_with_title),
                            onClick = {
                                val intent = NavigationIntent.NavigateToSend(
                                    uri = fileUrl,
                                    fileName = fileName,
                                )
                                navigate(intent)
                            },
                        )
                    }
                    this += FlatItemAction(
                        leading = icon(Icons.Outlined.Delete),
                        title = translatorScope.translate(Res.strings.file_action_delete_local_title),
                        onClick = ::performRemove,
                    )
                }
            }
        }
        if (launchViewCipherData != null) {
            section {
                this += FlatItemAction(
                    leading = icon(Icons.Outlined.OpenInNew),
                    trailing = {
                        ChevronIcon()
                    },
                    title = translatorScope.translate(Res.strings.file_action_view_cipher_title),
                    onClick = {
                        val route = VaultViewRoute(
                            itemId = launchViewCipherData.cipherId,
                            accountId = launchViewCipherData.accountId,
                        )
                        val intent = NavigationIntent.NavigateToRoute(
                            route = route,
                        )
                        navigate(intent)
                    },
                )
            }
        }
    }
}
