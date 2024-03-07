package com.artemchep.keyguard.feature.filter.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.service.filter.RemoveCipherFilterById
import com.artemchep.keyguard.common.service.filter.RenameCipherFilter
import com.artemchep.keyguard.common.service.filter.model.RenameCipherFilterRequest
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.filter.CipherFiltersRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardCipherFilter
import com.artemchep.keyguard.ui.icons.KeyguardWordlist
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.icons.iconSmall

object CipherFilterUtil {
    context(RememberStateFlowScope)
    fun onRename(
        renameCipherFilter: RenameCipherFilter,
        model: DCipherFilter,
    ) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = model.name,
            title = translate(Res.strings.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val items = listOfNotNull(
            nameItem,
        )
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(
                        main = Icons.Outlined.KeyguardCipherFilter,
                        secondary = Icons.Outlined.Edit,
                    ),
                    title = translate(Res.strings.customfilters_edit_filter_title),
                    items = items,
                    docUrl = null,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver

                val request = RenameCipherFilterRequest(
                    id = model.idRaw,
                    name = name,
                )
                renameCipherFilter(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    context(RememberStateFlowScope)
    fun onDeleteByItems(
        removeCipherFilterById: RemoveCipherFilterById,
        items: List<DCipherFilter>,
    ) {
        val title = if (items.size > 1) {
            translate(Res.strings.customfilters_delete_many_confirmation_title)
        } else {
            translate(Res.strings.customfilters_delete_one_confirmation_title)
        }
        val message = items
            .joinToString(separator = "\n") { it.name }
        val intent = createConfirmationDialogIntent(
            icon = icon(Icons.Outlined.Delete),
            title = title,
            message = message,
        ) {
            val ids = items
                .map { it.idRaw }
                .toSet()
            removeCipherFilterById(ids)
                .launchIn(appScope)
        }
        navigate(intent)
    }
}

context(RememberStateFlowScope)
expect fun CipherFilterUtil.addShortcutActionOrNull(
    filter: DCipherFilter,
): FlatItemAction?
