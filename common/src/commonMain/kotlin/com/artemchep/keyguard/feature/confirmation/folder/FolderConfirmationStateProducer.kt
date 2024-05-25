package com.artemchep.keyguard.feature.confirmation.folder

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.runtime.Composable
import arrow.core.andThen
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedTitle
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfoType
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private data class FolderVariant(
    val accountId: String,
    val folder: FolderInfo,
    val name: String,
    val enabled: Boolean,
) {
    sealed interface FolderInfo {
        val key: String

        data object None : FolderInfo {
            override val key: String = "none"
        }

        data object New : FolderInfo {
            override val key: String = "new"
        }

        data class Id(
            val id: String,
        ) : FolderInfo {
            override val key: String = "id:$id"
        }

        fun type() = when (this) {
            is None -> FolderInfoType.None
            is New -> FolderInfoType.New
            is Id -> FolderInfoType.Id
        }

        fun folderId() = when (this) {
            is None -> null
            is New -> null
            is Id -> id
        }
    }

    val key = "$accountId|${folder.key}"
}

@LeParcelize
@kotlinx.serialization.Serializable
data class FooBar(
    val folderType: FolderInfoType = FolderInfoType.Id,
    val folderId: String? = null,
) : LeParcelable

private fun FooBar.withFolderId(folder: FolderVariant.FolderInfo): FooBar {
    val folderType = folder.type()
    val folderId = (folder as? FolderVariant.FolderInfo.Id)?.id
    if (
        this.folderType == folderType &&
        this.folderId == folderId
    ) {
        return this
    }
    return FooBar(
        folderType = folderType,
        folderId = folderId,
    )
}

@Composable
fun folderConfirmationState(
    args: FolderConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<FolderConfirmationResult>,
): FolderConfirmationState = with(localDI().direct) {
    folderConfirmationState(
        args = args,
        transmitter = transmitter,
        getFolders = instance(),
        windowCoroutineScope = instance(),
    )
}

@Composable
fun folderConfirmationState(
    args: FolderConfirmationRoute.Args,
    transmitter: RouteResultTransmitter<FolderConfirmationResult>,
    getFolders: GetFolders,
    windowCoroutineScope: WindowCoroutineScope,
): FolderConfirmationState = produceScreenState(
    key = "folder_confirmation",
    initial = FolderConfirmationState(),
    args = arrayOf(
        getFolders,
        windowCoroutineScope,
    ),
) {
    val folderNameSink = mutablePersistedFlow("folder_name") {
        ""
    }
    val folderNameState = mutableComposeState(folderNameSink)
    val folderValidatedFlow = folderNameSink
        .validatedTitle(this)
        .shareInScreenScope()

    val foldersFlow = getFolders()
        .map { folders ->
            val items = folders
                .filter { it.accountId == args.accountId.id }
                .sortedWith(StringComparatorIgnoreCase { it.name })
                .map { folder ->
                    val folderEnabled = folder.id !in args.blacklistedFolderIds
                    val folderInfo = FolderVariant.FolderInfo.Id(
                        id = folder.id,
                    )
                    FolderVariant(
                        accountId = folder.accountId,
                        folder = folderInfo,
                        name = folder.name,
                        enabled = folderEnabled,
                    )
                }
                .toMutableList()
            // No folder item
            run {
                val enabled = null !in args.blacklistedFolderIds
                items += FolderVariant(
                    accountId = args.accountId.id,
                    folder = FolderVariant.FolderInfo.None,
                    name = translate(Res.string.folder_none),
                    enabled = enabled,
                )
            }
            // New folder item
            run {
                items += FolderVariant(
                    accountId = args.accountId.id,
                    folder = FolderVariant.FolderInfo.New,
                    name = translate(Res.string.folder_new),
                    enabled = true,
                )
            }
            items
        }
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    val selectionSink = mutablePersistedFlow<FooBar>("selection") {
        FooBar()
    }
    val selectionFlow = foldersFlow
        .combine(selectionSink) { folders, selection ->
            var s = selection

            val filteredFolders = folders
                .filter { it.enabled }
            // De-select non-existent folder
            if (s.folderType == FolderInfoType.Id) {
                val hasSelectedFolder = filteredFolders
                    .any { variant ->
                        when (val f = variant.folder) {
                            is FolderVariant.FolderInfo.Id -> f.id == s.folderId
                            else -> false
                        }
                    }
                if (!hasSelectedFolder) {
                    val newVariant = FolderVariant.FolderInfo.None
                    s = s.withFolderId(newVariant)
                }
            }
            // Pre-select the only available folder
            if (filteredFolders.size == 1 && s.folderId == null) {
                val folderId = filteredFolders.first().folder
                s = s.withFolderId(folderId)
            }
            s
        }
        .distinctUntilChanged()
        .shareIn(screenScope, SharingStarted.Lazily, replay = 1)

    fun onClickFolder(folder: FolderVariant.FolderInfo) = selectionSink.update {
        it.withFolderId(folder)
    }

    val folderItemsFlow = combine(
        foldersFlow,
        selectionFlow
            .distinctUntilChanged(),
    ) { folders, selection ->
        val selectedFolderType = selection.folderType
        val selectedFolderId = selection.folderId
        val items = folders
            .asSequence()
            .map { folder ->
                val selected = folder.folder.type() == selectedFolderType &&
                        folder.folder.folderId() == selectedFolderId
                val onClick = if (
                    folder.enabled
                ) {
                    ::onClickFolder.partially1(folder.folder)
                } else {
                    null
                }
                val icon = when (folder.folder) {
                    is FolderVariant.FolderInfo.None -> Icons.Outlined.FolderOff
                    is FolderVariant.FolderInfo.New -> Icons.Outlined.Add
                    else -> null
                }
                FolderConfirmationState.Content.Item(
                    key = folder.key,
                    icon = icon,
                    title = folder.name,
                    selected = selected,
                    onClick = onClick,
                )
            }
            .toList()
        items
    }
    val folderNameFlow = combine(
        selectionFlow
            .map { it.folderType }
            .distinctUntilChanged(),
        folderValidatedFlow,
    ) { folderType, folderNameValidated ->
        when (folderType) {
            FolderInfoType.New -> TextFieldModel2(
                state = folderNameState,
                text = folderNameValidated.model,
                error = (folderNameValidated as? Validated.Failure)?.error,
                onChange = folderNameState::value::set,
            )

            else -> null
        }
    }
    val contentFlow = combine(
        folderItemsFlow,
        folderNameFlow,
    ) { items, new ->
        FolderConfirmationState.Content(
            items = items,
            new = new,
        )
    }
    val confirmFlow = combine(
        selectionFlow,
        folderValidatedFlow,
    ) { selection, folderNameValidated ->
        val onConfirm = if (
            (selection.folderType != FolderInfoType.New || folderNameValidated is Validated.Success)
        ) {
            val folder = when (selection.folderType) {
                FolderInfoType.None -> FolderInfo.None
                FolderInfoType.New -> FolderInfo.New(name = folderNameValidated.model)
                FolderInfoType.Id -> {
                    val id = selection.folderId
                        ?: return@combine null // must not be null
                    FolderInfo.Id(id)
                }
            }
            val result = FolderConfirmationResult.Confirm(
                folderInfo = folder,
            )
            // lambda
            transmitter
                .partially1(result)
                .andThen {
                    navigatePopSelf()
                }
        } else {
            null
        }
        onConfirm
    }
    combine(
        contentFlow,
        confirmFlow,
    ) { content, onConfirm ->
        FolderConfirmationState(
            content = Loadable.Ok(content),
            onDeny = {
                transmitter(FolderConfirmationResult.Deny)
                navigatePopSelf()
            },
            onConfirm = onConfirm,
        )
    }
}
