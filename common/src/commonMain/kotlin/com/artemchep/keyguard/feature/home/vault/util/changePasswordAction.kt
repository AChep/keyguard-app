package com.artemchep.keyguard.feature.home.vault.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.artemchep.keyguard.common.io.biFlatTap
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.FolderOwnership2
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.usecase.ChangeCipherNameById
import com.artemchep.keyguard.common.usecase.ChangeCipherPasswordById
import com.artemchep.keyguard.common.usecase.CipherMerge
import com.artemchep.keyguard.common.usecase.CopyCipherById
import com.artemchep.keyguard.common.usecase.MoveCipherToFolderById
import com.artemchep.keyguard.common.usecase.RePromptCipherById
import com.artemchep.keyguard.common.usecase.RemoveCipherById
import com.artemchep.keyguard.common.usecase.RestoreCipherById
import com.artemchep.keyguard.common.usecase.TrashCipherById
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.folder.FolderConfirmationResult
import com.artemchep.keyguard.feature.confirmation.folder.FolderConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.organization.FolderInfo
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.LeAddRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultViewPasswordHistoryRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.badgeContainer

fun RememberStateFlowScope.cipherEnableConfirmAccessAction(
    rePromptCipherById: RePromptCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    FlatItemAction(
        icon = Icons.Filled.Lock,
        title = translate(Res.strings.ciphers_action_enable_auth_reprompt_title),
        onClick = {
            before?.invoke()

            val filteredCipherIds = ciphers
                .asSequence()
                .filter { !it.reprompt }
                .map { it.id }
                .toSet()
            rePromptCipherById(
                filteredCipherIds,
                true,
            )
                .effectMap {
                    val message = ToastMessage(
                        title = "Auth re-prompt enabled",
                    )
                    message(message)
                }
                .launchIn(appScope)
        },
    )
}

fun RememberStateFlowScope.cipherDisableConfirmAccessAction(
    rePromptCipherById: RePromptCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    FlatItemAction(
        icon = Icons.Outlined.LockOpen,
        title = translate(Res.strings.ciphers_action_disable_auth_reprompt_title),
        onClick = {
            before?.invoke()

            val filteredCipherIds = ciphers
                .asSequence()
                .filter { it.reprompt }
                .map { it.id }
                .toSet()
            rePromptCipherById(
                filteredCipherIds,
                false,
            )
                .effectMap {
                    val message = ToastMessage(
                        title = "Auth re-prompt disabled",
                    )
                    message(message)
                }
                .biFlatTap(
                    ifException = {
                        ioEffect { after?.invoke(false) }
                    },
                    ifSuccess = {
                        ioEffect { after?.invoke(true) }
                    },
                )
                .launchIn(appScope)
        },
    )
}

fun RememberStateFlowScope.cipherEditAction(
    cipher: DSecret,
    behavior: AddRoute.Args.Behavior = AddRoute.Args.Behavior(),
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Edit)
    val title = translate(Res.strings.ciphers_action_edit_title)
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val route = LeAddRoute(
                args = AddRoute.Args(
                    behavior = behavior,
                    initialValue = cipher,
                ),
            )
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
            // TODO: Where do I put 'after' callback?
        },
    )
}

fun RememberStateFlowScope.cipherMergeIntoAction(
    cipherMerge: CipherMerge,
    ciphers: List<DSecret>,
    behavior: AddRoute.Args.Behavior = AddRoute.Args.Behavior(),
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Merge)
    val title = translate(Res.strings.ciphers_action_merge_title)
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()
            cipherMergeInto(
                cipherMerge = cipherMerge,
                ciphers = ciphers,
                behavior = behavior,
            )
            // TODO: Where do I put 'after' callback?
        },
    )
}

fun RememberStateFlowScope.cipherMergeInto(
    cipherMerge: CipherMerge,
    ciphers: List<DSecret>,
    behavior: AddRoute.Args.Behavior = AddRoute.Args.Behavior(),
) = kotlin.run {
    val cipher = cipherMerge.invoke(ciphers)
    val type = ciphers.firstOrNull()?.type
    val route = LeAddRoute(
        args = AddRoute.Args(
            behavior = behavior,
            initialValue = cipher,
            ownershipRo = false,
            type = type,
            merge = AddRoute.Args.Merge(
                ciphers = ciphers,
            ),
        ),
    )
    val intent = NavigationIntent.NavigateToRoute(route)
    navigate(intent)
}

fun RememberStateFlowScope.cipherCopyToAction(
    copyCipherById: CopyCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val iconImageVector = Icons.Outlined.FileCopy
    val title = translate(Res.strings.ciphers_action_copy_title)
    FlatItemAction(
        icon = iconImageVector,
        title = title,
        onClick = {
            before?.invoke()

            val ciphersHaveAttachments = ciphers.any { it.attachments.isNotEmpty() }
            val note = when {
                ciphersHaveAttachments -> {
                    val text =
                        "Copying a cipher does not copy its attachments, this feature is work in progress."
                    SimpleNote(
                        text = text,
                        type = SimpleNote.Type.INFO,
                    )
                }

                else -> null
            }
            val route = registerRouteResultReceiver(
                route = OrganizationConfirmationRoute(
                    args = OrganizationConfirmationRoute.Args(
                        decor = OrganizationConfirmationRoute.Args.Decor(
                            title = title,
                            note = note,
                            icon = iconImageVector,
                        ),
                    ),
                ),
            ) { result ->
                if (result is OrganizationConfirmationResult.Confirm) {
                    val ownership = CreateRequest.Ownership2(
                        accountId = result.accountId,
                        folder = result.folderId,
                        organizationId = result.organizationId,
                        collectionIds = result.collectionsIds,
                    )
                    val cipherIdsToOwnership = ciphers
                        .associate { cipher ->
                            cipher.id to ownership
                        }
                    copyCipherById(cipherIdsToOwnership)
                        .effectMap {
                            val message = ToastMessage(
                                title = "Copied ciphers!",
                            )
                            message(message)
                        }
                        .launchIn(appScope)
                }

                val success = result is OrganizationConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherMoveToFolderAction(
    moveCipherToFolderById: MoveCipherToFolderById,
    accountId: AccountId,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Folder)
    val title = translate(Res.strings.ciphers_action_change_folder_title)
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val blacklistedFolderIds = ciphers
                .asSequence()
                .map { it.folderId }
                .toSet()
                // only if has one value
                .takeIf { it.size == 1 }
                .orEmpty()
            val route = registerRouteResultReceiver(
                route = FolderConfirmationRoute(
                    args = FolderConfirmationRoute.Args(
                        accountId = accountId,
                        blacklistedFolderIds = blacklistedFolderIds,
                    ),
                ),
            ) { result ->
                if (result is FolderConfirmationResult.Confirm) {
                    // Filter out the ciphers that already have the
                    // target folder. This is just to speed up the
                    // change process.
                    val cipherIds = when (val info = result.folderInfo) {
                        is FolderInfo.None,
                        is FolderInfo.Id,
                        -> {
                            val newFolderId = when (info) {
                                is FolderInfo.None -> null
                                is FolderInfo.Id -> info.id
                                else -> error("Unreachable statement!")
                            }
                            ciphers
                                .asSequence()
                                .filter { it.folderId != newFolderId }
                        }

                        is FolderInfo.New -> {
                            ciphers
                                .asSequence()
                        }
                    }
                        // Just in case we messed up before, make sure that
                        // the ciphers all belong to the target account.
                        .filter { it.accountId == accountId.id }
                        .map { it.id }
                        .toSet()
                    val destination = FolderOwnership2(
                        accountId = accountId.id,
                        folder = result.folderInfo,
                    )
                    moveCipherToFolderById(
                        cipherIds,
                        destination,
                    )
                        .effectMap {
                            val message = ToastMessage(
                                title = "Moved to the folder",
                            )
                            message(message)
                        }
                        .launchIn(appScope)
                }

                val success = result is FolderConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherChangeNameAction(
    changeCipherNameById: ChangeCipherNameById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Edit)
    val title = if (ciphers.size > 1) {
        translate(Res.strings.ciphers_action_change_names_title)
    } else {
        translate(Res.strings.ciphers_action_change_name_title)
    }
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val items = ciphers
                .sortedWith(StringComparatorIgnoreCase { it.name })
                .map { cipher ->
                    ConfirmationRoute.Args.Item.StringItem(
                        key = cipher.id,
                        value = cipher.name,
                        title = cipher.name,
                        type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
                        canBeEmpty = false,
                    )
                }
            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon,
                        title = title,
                        items = items,
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIdsToNames = result
                        .data
                        .mapValues { it.value as String }
                    changeCipherNameById(cipherIdsToNames)
                        .effectMap {
                            val message = ToastMessage(
                                title = "Changed names",
                            )
                            message(message)
                        }
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherChangePasswordAction(
    changeCipherPasswordById: ChangeCipherPasswordById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Password)
    val title = kotlin.run {
        val hasPasswords = ciphers.any { !it.login?.password.isNullOrEmpty() }
        if (hasPasswords) {
            if (ciphers.size > 1) {
                translate(Res.strings.ciphers_action_change_passwords_title)
            } else {
                translate(Res.strings.ciphers_action_change_password_title)
            }
        } else {
            if (ciphers.size > 1) {
                translate(Res.strings.ciphers_action_add_passwords_title)
            } else {
                translate(Res.strings.ciphers_action_add_password_title)
            }
        }
    }
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val items = ciphers
                .filter { it.type == DSecret.Type.Login }
                .sortedWith(StringComparatorIgnoreCase { it.name })
                .map { cipher ->
                    ConfirmationRoute.Args.Item.StringItem(
                        key = cipher.id,
                        value = cipher.login?.password.orEmpty(),
                        title = cipher.name,
                        type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
                        canBeEmpty = true, // so you can clear passwords
                    )
                }
            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon,
                        title = title,
                        message = translate(Res.strings.generator_password_note, 16),
                        items = items,
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIdsToPasswords = result
                        .data
                        .mapValues { it.value as String }
                    changeCipherPasswordById(cipherIdsToPasswords)
                        .effectMap {
                            val message = ToastMessage(
                                title = "Changed passwords",
                            )
                            message(message)
                        }
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
fun RememberStateFlowScope.cipherViewPasswordHistoryAction(
    cipher: DSecret,
    before: (() -> Unit)? = null,
) = kotlin.run {
    val title = translate(Res.strings.ciphers_action_view_password_history_title)
    FlatItemAction(
        leading = {
            BadgedBox(
                badge = {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.badgeContainer,
                    ) {
                        val size = cipher.login?.passwordHistory?.size ?: 0
                        Text(text = size.toString())
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                )
            }
        },
        title = title,
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val intent = NavigationIntent.NavigateToRoute(
                VaultViewPasswordHistoryRoute(
                    itemId = cipher.id,
                ),
            )
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherTrashAction(
    trashCipherById: TrashCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.Delete)
    val title = translate(Res.strings.ciphers_action_trash_title)
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.Delete),
                        title = "Move to trash?",
                        message = "Items that have been in Trash more than 30 days will be automatically deleted.",
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIds = ciphers
                        .map { it.id }
                        .toSet()
                    trashCipherById(cipherIds)
                        .effectMap {
                            val message = ToastMessage(
                                title = "Trashed",
                            )
                            message(message)
                        }
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherRestoreAction(
    restoreCipherById: RestoreCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.RestoreFromTrash)
    val title = translate(Res.strings.ciphers_action_restore_title)
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.RestoreFromTrash),
                        title = "Restore from trash?",
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIds = ciphers
                        .map { it.id }
                        .toSet()
                    restoreCipherById(cipherIds)
                        .effectMap {
                            val message = ToastMessage(
                                title = "Restored",
                            )
                            message(message)
                        }
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}

fun RememberStateFlowScope.cipherDeleteAction(
    removeCipherById: RemoveCipherById,
    ciphers: List<DSecret>,
    before: (() -> Unit)? = null,
    after: ((Boolean) -> Unit)? = null,
) = kotlin.run {
    val icon = icon(Icons.Outlined.DeleteForever)
    val title = translate(Res.strings.ciphers_action_delete_title)
    FlatItemAction(
        leading = icon,
        title = title,
        onClick = {
            before?.invoke()

            val route = registerRouteResultReceiver(
                route = ConfirmationRoute(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.DeleteForever),
                        title = "Delete forever?",
                    ),
                ),
            ) { result ->
                if (result is ConfirmationResult.Confirm) {
                    val cipherIds = ciphers
                        .map { it.id }
                        .toSet()
                    removeCipherById(cipherIds)
                        .effectMap {
                            val message = ToastMessage(
                                title = "Deleted",
                            )
                            message(message)
                        }
                        .launchIn(appScope)
                }

                val success = result is ConfirmationResult.Confirm
                after?.invoke(success)
            }
            val intent = NavigationIntent.NavigateToRoute(route)
            navigate(intent)
        },
    )
}
