package com.artemchep.keyguard.feature.send.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import arrow.core.some
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.PatchSendRequest
import com.artemchep.keyguard.common.usecase.PatchSendById
import com.artemchep.keyguard.common.usecase.RemoveSendById
import com.artemchep.keyguard.common.usecase.SendToolbox
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.onClick
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.Selection
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.icons.Stub
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall
import com.artemchep.keyguard.ui.selection.SelectionHandle
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

object SendUtil {
    context(stateScope: RememberStateFlowScope)
    fun renameActionOrNull(
        confirmationRouteFactory: ConfirmationRouteFactory,
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canEdit) {
            return@with null
        }

        val icon = icon(Icons.Outlined.Edit)
        val title = if (sends.size > 1) {
            Res.string.sends_action_change_names_title.wrap()
        } else {
            Res.string.sends_action_change_name_title.wrap()
        }
        FlatItemAction(
            id = "send.rename",
            leading = icon,
            title = title,
            onClick = onClick {
                before?.invoke()

                val items = sends
                    .sorted()
                    .map { cipher ->
                        ConfirmationRoute.Args.Item.StringItem(
                            key = cipher.id,
                            value = cipher.name,
                            title = cipher.name,
                            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
                            canBeEmpty = false,
                        )
                    }
                val route = confirmationRouteFactory.registerRouteResultReceiver(
                    args = ConfirmationRoute.Args(
                        icon = icon,
                        title = translate(title),
                        items = items,
                    ),
                ) { result ->
                    if (result is ConfirmationResult.Confirm) {
                        val request = createPatchRequestMultiple(result.data) { name ->
                            val nameFixed = name as String
                            PatchSendRequest.Data(
                                name = nameFixed.some(),
                            )
                        }
                        patchSendById(request)
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

    context(stateScope: RememberStateFlowScope)
    fun renameFileActionOrNull(
        confirmationRouteFactory: ConfirmationRouteFactory,
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canEdit) {
            return@with null
        }

        val allFiles = sends.all { it.file != null }
        if (!allFiles) {
            return@with null
        }

        // TODO: Seems like at this moment we can not change the file name
        if (isRelease) return@with null

        val icon = iconSmall(Icons.Outlined.Attachment, Icons.Outlined.Edit)
        val title = if (sends.size > 1) {
            Res.string.sends_action_change_filenames_title
        } else {
            Res.string.sends_action_change_filename_title
        }.wrap()
        FlatItemAction(
            id = "send.renameFile",
            leading = icon,
            title = title,
            onClick = onClick {
                before?.invoke()

                val items = sends
                    .sorted()
                    .map { cipher ->
                        ConfirmationRoute.Args.Item.StringItem(
                            key = cipher.id,
                            value = cipher.file?.fileName.orEmpty(),
                            title = cipher.name,
                            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
                            canBeEmpty = false,
                        )
                    }
                val route = confirmationRouteFactory.registerRouteResultReceiver(
                    args = ConfirmationRoute.Args(
                        icon = icon,
                        title = translate(title),
                        items = items,
                    ),
                ) { result ->
                    if (result is ConfirmationResult.Confirm) {
                        val request = createPatchRequestMultiple(result.data) { name ->
                            val nameFixed = name as String
                            PatchSendRequest.Data(
                                fileName = nameFixed.some(),
                            )
                        }
                        patchSendById(request)
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

    context(stateScope: RememberStateFlowScope)
    suspend fun changePasswordActionOrNull(
        confirmationRouteFactory: ConfirmationRouteFactory,
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canEdit) {
            return@with null
        }

        val hasPasswords = sends.any { it.hasPassword }

        val icon = iconSmall(Icons.Outlined.Password)
        val title = if (hasPasswords) {
            if (sends.size > 1) {
                Res.string.sends_action_change_passwords_title
            } else {
                Res.string.sends_action_change_password_title
            }
        } else {
            if (sends.size > 1) {
                Res.string.sends_action_set_passwords_title
            } else {
                Res.string.sends_action_set_password_title
            }
        }.wrap()
        FlatItemAction(
            id = "send.changePassword",
            leading = icon,
            title = title,
            onClick = onClick {
                before?.invoke()

                val items = sends
                    .sorted()
                    .map { send ->
                        ConfirmationRoute.Args.Item.StringItem(
                            key = send.id,
                            value = "",
                            title = send.name,
                            type = ConfirmationRoute.Args.Item.StringItem.Type.Password,
                            canBeEmpty = true, // so you can clear passwords
                        )
                    }
                val route = confirmationRouteFactory.registerRouteResultReceiver(
                    args = ConfirmationRoute.Args(
                        icon = icon,
                        title = translate(title),
                        message = if (hasPasswords) {
                            null
                        } else {
                            translate(Res.string.sends_action_set_password_confirmation_message)
                        },
                        items = items,
                    ),
                ) { result ->
                    if (result is ConfirmationResult.Confirm) {
                        val request = createPatchRequestMultiple(result.data) { password ->
                            val passwordFixed = (password as String)
                                .takeIf { it.isNotEmpty() }
                            PatchSendRequest.Data(
                                password = passwordFixed.some(),
                            )
                        }
                        patchSendById(request)
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

    context(stateScope: RememberStateFlowScope)
    fun removePasswordActionOrNull(
        confirmationRouteFactory: ConfirmationRouteFactory,
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canEdit) {
            return@with null
        }

        val hasPasswords = sends.any { it.hasPassword }
        if (!hasPasswords) {
            return@with null
        }

        val icon = iconSmall(Icons.Outlined.Password, Icons.Outlined.Remove)
        val title = if (sends.size > 1) {
            Res.string.sends_action_remove_passwords_title
        } else {
            Res.string.sends_action_remove_password_title
        }.wrap()
        FlatItemAction(
            id = "send.removePassword",
            leading = icon,
            title = title,
            onClick = onClick {
                before?.invoke()

                val route = confirmationRouteFactory.registerRouteResultReceiver(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.Password, Icons.Outlined.Remove),
                        title = if (sends.size > 1) {
                            translate(Res.string.sends_action_remove_passwords_confirmation_title)
                        } else {
                            translate(Res.string.sends_action_remove_password_confirmation_title)
                        },
                        message = translate(Res.string.sends_action_remove_password_confirmation_message),
                    ),
                ) { result ->
                    if (result is ConfirmationResult.Confirm) {
                        val request = createPatchRequestSingle(sends) {
                            PatchSendRequest.Data(
                                password = null.some(),
                            )
                        }
                        patchSendById(request)
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

    context(stateScope: RememberStateFlowScope)
    fun showEmailActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canEdit) {
            return@with null
        }
        val canShowEmail = sends.any { it.hideEmail }
        if (!canShowEmail) {
            return@with null
        }
        showEmailAction(
            patchSendById = patchSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(stateScope: RememberStateFlowScope)
    fun showEmailAction(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        val icon = iconSmall(Icons.Outlined.Email, Icons.Outlined.Visibility)
        val title = Res.string.sends_action_show_email_title.wrap()
        FlatItemAction(
            id = "send.showEmail",
            leading = icon,
            title = title,
            onClick = {
                before?.invoke()

                val request = createPatchRequestSingle(sends) {
                    PatchSendRequest.Data(
                        hideEmail = false.some(),
                    )
                }
                patchSendById(request)
                    .launchIn(appScope)
            },
        )
    }

    context(stateScope: RememberStateFlowScope)
    fun hideEmailActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canEdit) {
            return@with null
        }
        val canHideEmail = sends.any { !it.hideEmail }
        if (!canHideEmail) {
            return@with null
        }
        hideEmailAction(
            patchSendById = patchSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(stateScope: RememberStateFlowScope)
    fun hideEmailAction(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        val icon = iconSmall(Icons.Outlined.Email, Icons.Outlined.VisibilityOff)
        val title = Res.string.sends_action_hide_email_title.wrap()
        FlatItemAction(
            id = "send.hideEmail",
            leading = icon,
            title = title,
            onClick = {
                before?.invoke()

                val request = createPatchRequestSingle(sends) {
                    PatchSendRequest.Data(
                        hideEmail = true.some(),
                    )
                }
                patchSendById(request)
                    .launchIn(appScope)
            },
        )
    }

    context(stateScope: RememberStateFlowScope)
    fun enableActionOrNull(
        confirmationRouteFactory: ConfirmationRouteFactory,
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canEdit) {
            return@with null
        }
        val canEnable = sends.any { it.disabled }
        if (!canEnable) {
            return@with null
        }
        enableAction(
            confirmationRouteFactory = confirmationRouteFactory,
            patchSendById = patchSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(stateScope: RememberStateFlowScope)
    fun enableAction(
        confirmationRouteFactory: ConfirmationRouteFactory,
        patchSendById: PatchSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        val icon = icon(Icons.Stub)
        val title = Res.string.sends_action_enable_title.wrap()
        val text = Res.string.sends_action_enable_text.wrap()
        FlatItemAction(
            id = "send.enable",
            leading = icon,
            title = title,
            text = text,
            onClick = onClick {
                before?.invoke()

                val route = confirmationRouteFactory.registerRouteResultReceiver(
                    args = ConfirmationRoute.Args(
                        title = translate(Res.string.sends_action_enable_confirmation_title),
                    ),
                ) { result ->
                    if (result is ConfirmationResult.Confirm) {
                        val request = createPatchRequestSingle(sends) {
                            PatchSendRequest.Data(
                                disabled = false.some(),
                            )
                        }
                        patchSendById(request)
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

    context(stateScope: RememberStateFlowScope)
    fun disableActionOrNull(
        confirmationRouteFactory: ConfirmationRouteFactory,
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canEdit) {
            return@with null
        }
        val canDisable = sends.any { !it.disabled }
        if (!canDisable) {
            return@with null
        }
        disableAction(
            confirmationRouteFactory = confirmationRouteFactory,
            patchSendById = patchSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(stateScope: RememberStateFlowScope)
    fun disableAction(
        confirmationRouteFactory: ConfirmationRouteFactory,
        patchSendById: PatchSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        val icon = icon(Icons.Stub)
        val title = Res.string.sends_action_disable_title.wrap()
        val text = Res.string.sends_action_disable_text.wrap()
        FlatItemAction(
            id = "send.disable",
            leading = icon,
            title = title,
            text = text,
            onClick = onClick {
                before?.invoke()

                val route = confirmationRouteFactory.registerRouteResultReceiver(
                    args = ConfirmationRoute.Args(
                        title = translate(Res.string.sends_action_disable_confirmation_title),
                    ),
                ) { result ->
                    if (result is ConfirmationResult.Confirm) {
                        val request = createPatchRequestSingle(sends) {
                            PatchSendRequest.Data(
                                disabled = true.some(),
                            )
                        }
                        patchSendById(request)
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

    context(stateScope: RememberStateFlowScope)
    fun deleteActionOrNull(
        confirmationRouteFactory: ConfirmationRouteFactory,
        removeSendById: RemoveSendById,
        sends: List<DSend>,
        canDelete: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        if (!canDelete) {
            return@with null
        }
        deleteAction(
            confirmationRouteFactory = confirmationRouteFactory,
            removeSendById = removeSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(stateScope: RememberStateFlowScope)
    fun deleteAction(
        confirmationRouteFactory: ConfirmationRouteFactory,
        removeSendById: RemoveSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = with(stateScope) {
        val icon = icon(Icons.Outlined.DeleteForever)
        val title = Res.string.sends_action_delete_title.wrap()
        FlatItemAction(
            id = "send.delete",
            leading = icon,
            title = title,
            onClick = onClick {
                before?.invoke()

                val route = confirmationRouteFactory.registerRouteResultReceiver(
                    args = ConfirmationRoute.Args(
                        icon = icon(Icons.Outlined.DeleteForever),
                        title = translate(Res.string.sends_action_delete_confirmation_title),
                    ),
                ) { result ->
                    if (result is ConfirmationResult.Confirm) {
                        val sendIds = sends
                            .map { it.id }
                            .toSet()
                        removeSendById(sendIds)
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

    private fun List<DSend>.sorted() = this
        .sortedWith(StringComparatorIgnoreCase { it.name })

    private fun createPatchRequestSingle(
        sends: List<DSend>,
        factory: () -> PatchSendRequest.Data,
    ): PatchSendRequest = kotlin.run {
        val data = factory()
        val patch = sends
            .associate {
                it.id to data
            }
        PatchSendRequest(
            patch = patch,
        )
    }

    private fun <T> createPatchRequestMultiple(
        data: Map<String, T>,
        factory: (T) -> PatchSendRequest.Data,
    ): PatchSendRequest = kotlin.run {
        val patch = data
            .mapValues {
                factory(it.value)
            }
        PatchSendRequest(
            patch = patch,
        )
    }

    context(stateScope: RememberStateFlowScope)
    suspend fun actions(
        confirmationRouteFactory: ConfirmationRouteFactory,
        toolbox: SendToolbox,
        sends: List<DSend>,
        canEdit: Boolean,
    ) = with(stateScope) {
        buildContextItems {
            section {
                this += renameActionOrNull(
                    confirmationRouteFactory = confirmationRouteFactory,
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += renameFileActionOrNull(
                    confirmationRouteFactory = confirmationRouteFactory,
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
            }
            section {
                this += changePasswordActionOrNull(
                    confirmationRouteFactory = confirmationRouteFactory,
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += removePasswordActionOrNull(
                    confirmationRouteFactory = confirmationRouteFactory,
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
            }
            section {
                this += showEmailActionOrNull(
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += hideEmailActionOrNull(
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
            }
            section {
                this += enableActionOrNull(
                    confirmationRouteFactory = confirmationRouteFactory,
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += disableActionOrNull(
                    confirmationRouteFactory = confirmationRouteFactory,
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += deleteActionOrNull(
                    confirmationRouteFactory = confirmationRouteFactory,
                    removeSendById = toolbox.removeSendById,
                    sends = sends,
                    canDelete = canEdit,
                )
            }
        }
    }

    //
    // Selection
    //

    fun canEditFlow(
        accountsFlow: Flow<List<DAccount>>,
        profilesFlow: Flow<List<DProfile>>,
        canWriteFlow: Flow<Boolean>,
    ) = kotlin.run {
        canWriteFlow
            .flatMapLatest { canWrite ->
                if (!canWrite) {
                    return@flatMapLatest flowOf(false)
                }

                // Here we might want to filter the accounts if
                // Bitwarden Send becomes unavailable for non
                // premium users.
                combine(
                    accountsFlow,
                    profilesFlow,
                ) { accounts, profiles ->
                    accounts.any { it.type.capabilities.supportsSends } &&
                            profiles.any { true }
                }
            }
            .distinctUntilChanged()
    }

    context(stateScope: RememberStateFlowScope)
    fun selectionFlow(
        selectionHandle: SelectionHandle,
        sendsFlow: Flow<List<DSend>>,
        canEditFlow: Flow<Boolean>,
        confirmationRouteFactory: ConfirmationRouteFactory,
        //
        toolbox: SendToolbox,
    ) = combine(
        sendsFlow,
        canEditFlow,
        selectionHandle.idsFlow,
    ) { sends, canEdit, selectedSendIds ->
        if (selectedSendIds.isEmpty()) {
            return@combine null
        }

        val selectedSends = sends
            .filter { it.id in selectedSendIds }
        val actions = actions(
            confirmationRouteFactory = confirmationRouteFactory,
            toolbox = toolbox,
            sends = selectedSends,
            canEdit = canEdit,
        )
        Selection(
            count = selectedSends.size,
            actions = actions.toPersistentList(),
            onClear = selectionHandle::clearSelection,
        )
    }
}
