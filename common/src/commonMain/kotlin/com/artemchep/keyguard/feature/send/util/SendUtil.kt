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
import com.artemchep.keyguard.common.model.DProfile
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.PatchSendRequest
import com.artemchep.keyguard.common.usecase.PatchSendById
import com.artemchep.keyguard.common.usecase.RemoveSendById
import com.artemchep.keyguard.common.usecase.SendToolbox
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
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
    context(RememberStateFlowScope)
    fun renameActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canEdit) {
            return@run null
        }

        val icon = icon(Icons.Outlined.Edit)
        val title = if (sends.size > 1) {
            translate(Res.strings.sends_action_change_names_title)
        } else {
            translate(Res.strings.sends_action_change_name_title)
        }
        FlatItemAction(
            leading = icon,
            title = title,
            onClick = {
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

    context(RememberStateFlowScope)
    fun renameFileActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canEdit) {
            return@run null
        }

        val allFiles = sends.all { it.file != null }
        if (!allFiles) {
            return@run null
        }

        // TODO: Seems like at this moment we can not change the file name
        if (isRelease) return@run null

        val icon = iconSmall(Icons.Outlined.Attachment, Icons.Outlined.Edit)
        val title = if (sends.size > 1) {
            translate(Res.strings.sends_action_change_filenames_title)
        } else {
            translate(Res.strings.sends_action_change_filename_title)
        }
        FlatItemAction(
            leading = icon,
            title = title,
            onClick = {
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

    context(RememberStateFlowScope)
    fun changePasswordActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canEdit) {
            return@run null
        }

        val hasPasswords = sends.any { it.hasPassword }

        val icon = iconSmall(Icons.Outlined.Password)
        val title = if (hasPasswords) {
            if (sends.size > 1) {
                translate(Res.strings.sends_action_change_passwords_title)
            } else {
                translate(Res.strings.sends_action_change_password_title)
            }
        } else {
            if (sends.size > 1) {
                translate(Res.strings.sends_action_set_passwords_title)
            } else {
                translate(Res.strings.sends_action_set_password_title)
            }
        }
        FlatItemAction(
            leading = icon,
            title = title,
            onClick = {
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
                val route = registerRouteResultReceiver(
                    route = ConfirmationRoute(
                        args = ConfirmationRoute.Args(
                            icon = icon,
                            title = title,
                            message = if (hasPasswords) {
                                null
                            } else {
                                translate(Res.strings.sends_action_set_password_confirmation_message)
                            },
                            items = items,
                        ),
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

    context(RememberStateFlowScope)
    fun removePasswordActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canEdit) {
            return@run null
        }

        val hasPasswords = sends.any { it.hasPassword }
        if (!hasPasswords) {
            return@run null
        }

        val icon = iconSmall(Icons.Outlined.Password, Icons.Outlined.Remove)
        val title = if (sends.size > 1) {
            translate(Res.strings.sends_action_remove_passwords_title)
        } else {
            translate(Res.strings.sends_action_remove_password_title)
        }
        FlatItemAction(
            leading = icon,
            title = title,
            onClick = {
                before?.invoke()

                val route = registerRouteResultReceiver(
                    route = ConfirmationRoute(
                        args = ConfirmationRoute.Args(
                            icon = icon(Icons.Outlined.Password, Icons.Outlined.Remove),
                            title = if (sends.size > 1) {
                                translate(Res.strings.sends_action_remove_passwords_confirmation_title)
                            } else {
                                translate(Res.strings.sends_action_remove_password_confirmation_title)
                            },
                            message = translate(Res.strings.sends_action_remove_password_confirmation_message),
                        ),
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

    context(RememberStateFlowScope)
    fun showEmailActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canEdit) {
            return@run null
        }
        val canShowEmail = sends.any { it.hideEmail }
        if (!canShowEmail) {
            return@run null
        }
        showEmailAction(
            patchSendById = patchSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(RememberStateFlowScope)
    fun showEmailAction(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        val icon = iconSmall(Icons.Outlined.Email, Icons.Outlined.Visibility)
        val title = translate(Res.strings.sends_action_show_email_title)
        FlatItemAction(
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

    context(RememberStateFlowScope)
    fun hideEmailActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canEdit) {
            return@run null
        }
        val canHideEmail = sends.any { !it.hideEmail }
        if (!canHideEmail) {
            return@run null
        }
        hideEmailAction(
            patchSendById = patchSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(RememberStateFlowScope)
    fun hideEmailAction(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        val icon = iconSmall(Icons.Outlined.Email, Icons.Outlined.VisibilityOff)
        val title = translate(Res.strings.sends_action_hide_email_title)
        FlatItemAction(
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

    context(RememberStateFlowScope)
    fun enableActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canEdit) {
            return@run null
        }
        val canEnable = sends.any { it.disabled }
        if (!canEnable) {
            return@run null
        }
        enableAction(
            patchSendById = patchSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(RememberStateFlowScope)
    fun enableAction(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        val icon = icon(Icons.Stub)
        val title = translate(Res.strings.sends_action_enable_title)
        val text = translate(Res.strings.sends_action_enable_text)
        FlatItemAction(
            leading = icon,
            title = title,
            text = text,
            onClick = {
                before?.invoke()

                val route = registerRouteResultReceiver(
                    route = ConfirmationRoute(
                        args = ConfirmationRoute.Args(
                            title = translate(Res.strings.sends_action_enable_confirmation_title),
                        ),
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

    context(RememberStateFlowScope)
    fun disableActionOrNull(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        canEdit: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canEdit) {
            return@run null
        }
        val canDisable = sends.any { !it.disabled }
        if (!canDisable) {
            return@run null
        }
        disableAction(
            patchSendById = patchSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(RememberStateFlowScope)
    fun disableAction(
        patchSendById: PatchSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        val icon = icon(Icons.Stub)
        val title = translate(Res.strings.sends_action_disable_title)
        val text = translate(Res.strings.sends_action_disable_text)
        FlatItemAction(
            leading = icon,
            title = title,
            text = text,
            onClick = {
                before?.invoke()

                val route = registerRouteResultReceiver(
                    route = ConfirmationRoute(
                        args = ConfirmationRoute.Args(
                            title = translate(Res.strings.sends_action_disable_confirmation_title),
                        ),
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

    context(RememberStateFlowScope)
    fun deleteActionOrNull(
        removeSendById: RemoveSendById,
        sends: List<DSend>,
        canDelete: Boolean,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        if (!canDelete) {
            return@run null
        }
        deleteAction(
            removeSendById = removeSendById,
            sends = sends,
            before = before,
            after = after,
        )
    }

    context(RememberStateFlowScope)
    fun deleteAction(
        removeSendById: RemoveSendById,
        sends: List<DSend>,
        before: (() -> Unit)? = null,
        after: ((Boolean) -> Unit)? = null,
    ) = kotlin.run {
        val icon = icon(Icons.Outlined.DeleteForever)
        val title = translate(Res.strings.sends_action_delete_title)
        FlatItemAction(
            leading = icon,
            title = title,
            onClick = {
                before?.invoke()

                val route = registerRouteResultReceiver(
                    route = ConfirmationRoute(
                        args = ConfirmationRoute.Args(
                            icon = icon(Icons.Outlined.DeleteForever),
                            title = translate(Res.strings.sends_action_delete_confirmation_title),
                        ),
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

    context(RememberStateFlowScope)
    fun actions(
        toolbox: SendToolbox,
        sends: List<DSend>,
        canEdit: Boolean,
    ) = kotlin.run {
        buildContextItems {
            section {
                this += renameActionOrNull(
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += renameFileActionOrNull(
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
            }
            section {
                this += changePasswordActionOrNull(
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += removePasswordActionOrNull(
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
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += disableActionOrNull(
                    patchSendById = toolbox.patchSendById,
                    sends = sends,
                    canEdit = canEdit,
                )
                this += deleteActionOrNull(
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
        profilesFlow: Flow<List<DProfile>>,
        canWriteFlow: Flow<Boolean>,
    ) = kotlin.run {
        canWriteFlow
            .flatMapLatest { canWrite ->
                if (!canWrite) {
                    return@flatMapLatest flowOf(false)
                }

                // Using send requires a user to have the
                // Bitwarden premium. We don't want to show the
                // new item button if a user won't be able to
                // create an item anyway.
                profilesFlow
                    .map { profiles ->
                        profiles.any { it.premium }
                    }
            }
            .distinctUntilChanged()
    }

    context(RememberStateFlowScope)
    fun selectionFlow(
        selectionHandle: SelectionHandle,
        sendsFlow: Flow<List<DSend>>,
        canEditFlow: Flow<Boolean>,
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
