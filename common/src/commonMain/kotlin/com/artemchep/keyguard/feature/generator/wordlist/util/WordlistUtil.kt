package com.artemchep.keyguard.feature.generator.wordlist.util

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.AddWordlistRequest
import com.artemchep.keyguard.common.model.DGeneratorWordlist
import com.artemchep.keyguard.common.model.EditWordlistRequest
import com.artemchep.keyguard.common.usecase.AddWordlist
import com.artemchep.keyguard.common.usecase.EditWordlist
import com.artemchep.keyguard.common.usecase.RemoveWordlistById
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.KeyguardCipherFilter
import com.artemchep.keyguard.ui.icons.KeyguardWordlist
import com.artemchep.keyguard.ui.icons.icon

object WordlistUtil {
    context(RememberStateFlowScope)
    fun onRename(
        editWordlist: EditWordlist,
        entity: DGeneratorWordlist,
    ) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = entity.name,
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
                    title = translate(Res.strings.wordlist_edit_wordlist_title),
                    items = items,
                    docUrl = null,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver

                val request = EditWordlistRequest(
                    id = entity.idRaw,
                    name = name,
                )
                editWordlist(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    context(RememberStateFlowScope)
    fun onNewFromFile(
        addWordlist: AddWordlist,
    ) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = "",
            title = translate(Res.strings.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val fileKey = "file"
        val fileItem = ConfirmationRoute.Args.Item.FileItem(
            key = fileKey,
            value = null,
            title = translate(Res.strings.wordlist),
        )

        val items = listOfNotNull(
            nameItem,
            fileItem,
        )
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(
                        main = Icons.Outlined.KeyguardWordlist,
                        secondary = Icons.Outlined.Add,
                    ),
                    title = translate(Res.strings.wordlist_add_wordlist_title),
                    items = items,
                    docUrl = null,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver
                val file = result.data[fileKey] as? ConfirmationRoute.Args.Item.FileItem.File
                    ?: return@registerRouteResultReceiver

                val wordlist = AddWordlistRequest.Wordlist.FromFile(
                    uri = file.uri,
                )
                val request = AddWordlistRequest(
                    name = name,
                    wordlist = wordlist,
                )
                addWordlist(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    context(RememberStateFlowScope)
    fun onNewFromUrl(
        addWordlist: AddWordlist,
    ) {
        val nameKey = "name"
        val nameItem = ConfirmationRoute.Args.Item.StringItem(
            key = nameKey,
            value = "",
            title = translate(Res.strings.generic_name),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
            canBeEmpty = false,
        )

        val urlKey = "url"
        val urlItem = ConfirmationRoute.Args.Item.StringItem(
            key = urlKey,
            value = "",
            title = translate(Res.strings.url),
            type = ConfirmationRoute.Args.Item.StringItem.Type.Command,
            canBeEmpty = false,
        )

        val items = listOfNotNull(
            nameItem,
            urlItem,
        )
        val route = registerRouteResultReceiver(
            route = ConfirmationRoute(
                args = ConfirmationRoute.Args(
                    icon = icon(
                        main = Icons.Outlined.KeyguardWordlist,
                        secondary = Icons.Outlined.Add,
                    ),
                    title = translate(Res.strings.wordlist_add_wordlist_title),
                    items = items,
                    docUrl = null,
                ),
            ),
        ) { result ->
            if (result is ConfirmationResult.Confirm) {
                val name = result.data[nameKey] as? String
                    ?: return@registerRouteResultReceiver
                val url = result.data[urlKey] as? String
                    ?: return@registerRouteResultReceiver

                val wordlist = AddWordlistRequest.Wordlist.FromUrl(
                    url = url,
                )
                val request = AddWordlistRequest(
                    name = name,
                    wordlist = wordlist,
                )
                addWordlist(request)
                    .launchIn(appScope)
            }
        }
        val intent = NavigationIntent.NavigateToRoute(route)
        navigate(intent)
    }

    context(RememberStateFlowScope)
    fun onDeleteByItems(
        removeWordlistById: RemoveWordlistById,
        items: List<DGeneratorWordlist>,
    ) {
        val title = if (items.size > 1) {
            translate(Res.strings.wordlist_delete_many_confirmation_title)
        } else {
            translate(Res.strings.wordlist_delete_one_confirmation_title)
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
            removeWordlistById(ids)
                .launchIn(appScope)
        }
        navigate(intent)
    }
}
