package com.artemchep.keyguard.wear.feature.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetFolders
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.common.util.StringComparatorIgnoreCase
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.VaultRouteFactory
import com.artemchep.keyguard.feature.home.vault.by
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.send.SendRouteFactory
import com.artemchep.keyguard.res.home_generator_label
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.folders
import com.artemchep.keyguard.res.home_favorites_label
import com.artemchep.keyguard.res.home_settings_label
import com.artemchep.keyguard.res.home_other_label
import com.artemchep.keyguard.res.home_send_label
import com.artemchep.keyguard.res.home_vault_label
import com.artemchep.keyguard.wear.feature.generator.WearGeneratorRoute
import com.artemchep.keyguard.wear.feature.settings.WearSettingsRoute
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

internal sealed interface WearHomeItemSpec {
    val id: String

    data class Section(
        override val id: String,
        val title: TextHolder,
    ) : WearHomeItemSpec

    data class Action(
        override val id: String,
        val title: TextHolder,
        val icon: ImageVector? = null,
        val destination: Destination,
    ) : WearHomeItemSpec
}

internal data class WearHomeContentSpec(
    val headerItem: WearHomeItemSpec.Action? = null,
    val items: List<WearHomeItemSpec> = emptyList(),
)

internal sealed interface Destination {
    data object Favorites : Destination
    data class Type(val value: DSecret.Type) : Destination
    data class Folder(val value: DFolder) : Destination
    data object Vault : Destination
    data object Sends : Destination
    data object Generator : Destination
    data object Settings : Destination
}

@Composable
internal fun wearHomeScreenState(): WearHomeState = with(localDI().direct) {
    wearHomeScreenState(
        getProfiles = instance(),
        getCiphers = instance(),
        getFolders = instance(),
        vaultRouteFactory = instance(),
        sendRouteFactory = instance(),
    )
}

@Composable
internal fun wearHomeScreenState(
    getProfiles: GetProfiles,
    getCiphers: GetCiphers,
    getFolders: GetFolders,
    vaultRouteFactory: VaultRouteFactory,
    sendRouteFactory: SendRouteFactory,
): WearHomeState = produceScreenState(
    key = "wear_home",
    initial = WearHomeState(),
    args = arrayOf(
        getProfiles,
        getCiphers,
        getFolders,
    ),
) {
    val visibleCiphersFlow = filterHiddenProfiles(
        getCiphers = getCiphers,
        getProfiles = getProfiles,
    ).map { ciphers ->
        ciphers.filter { cipher ->
            cipher.deletedDate == null && cipher.archivedDate == null
        }
    }
    val visibleFoldersFlow = filterHiddenProfiles(
        getFolders = getFolders,
        getProfiles = getProfiles,
    ).map { folders ->
        folders
            .filterNot(DFolder::deleted)
            .sortedWith(StringComparatorIgnoreCase { it.name })
    }

    combine(
        visibleCiphersFlow,
        visibleFoldersFlow,
    ) { ciphers, folders ->
        val content = createWearHomeContentSpec(
            ciphers = ciphers,
            folders = folders,
        )
        WearHomeState(
            headerItem = content.headerItem?.let { item ->
                toStateAction(
                    item = item,
                    vaultRouteFactory = vaultRouteFactory,
                    sendRouteFactory = sendRouteFactory,
                )
            },
            items = content.items.map { item ->
                when (item) {
                    is WearHomeItemSpec.Action -> toStateAction(
                        item = item,
                        vaultRouteFactory = vaultRouteFactory,
                        sendRouteFactory = sendRouteFactory,
                    )

                    is WearHomeItemSpec.Section -> WearHomeState.Item.Section(
                        id = item.id,
                        title = item.title,
                    )
                }
            }.toPersistentList(),
        )
    }
}

private suspend fun RememberStateFlowScope.toStateAction(
    item: WearHomeItemSpec.Action,
    vaultRouteFactory: VaultRouteFactory,
    sendRouteFactory: SendRouteFactory,
): WearHomeState.Item.Action {
    val route = routeForDestination(
        destination = item.destination,
        vaultRouteFactory = vaultRouteFactory,
        sendRouteFactory = sendRouteFactory,
    )
    return WearHomeState.Item.Action(
        id = item.id,
        title = item.title,
        icon = item.icon,
        onClick = {
            navigate(
                NavigationIntent.NavigateToRoute(route),
            )
        },
    )
}

private suspend fun RememberStateFlowScope.routeForDestination(
    destination: Destination,
    vaultRouteFactory: VaultRouteFactory,
    sendRouteFactory: SendRouteFactory,
): Route = when (destination) {
    Destination.Favorites -> vaultRouteFactory.create(
        args = VaultRoute.Args(
            appBar = VaultRoute.Args.AppBar(
                title = translate(Res.string.home_favorites_label),
            ),
            filter = DFilter.ByFavorite,
        ),
    )

    is Destination.Folder -> vaultRouteFactory.by(destination.value)

    is Destination.Type -> vaultRouteFactory.create(
        args = VaultRoute.Args(
            appBar = VaultRoute.Args.AppBar(
                title = translate(destination.value.titleH()),
            ),
            filter = DFilter.ByType(destination.value),
        ),
    )

    Destination.Settings -> WearSettingsRoute
    Destination.Vault -> vaultRouteFactory.create(
        args = VaultRoute.Args(
            appBar = VaultRoute.Args.AppBar(
                title = translate(Res.string.home_vault_label),
            ),
        ),
    )

    Destination.Sends -> sendRouteFactory.create(
        args = SendRoute.Args(
            appBar = SendRoute.Args.AppBar(
                title = translate(Res.string.home_send_label),
            ),
        ),
    )

    Destination.Generator -> WearGeneratorRoute()
}

internal fun createWearHomeContentSpec(
    ciphers: List<DSecret>,
    folders: List<DFolder>,
): WearHomeContentSpec {
    val nonEmptyFolderKeys = ciphers
        .asSequence()
        .mapNotNull { cipher ->
            val folderId = cipher.folderId ?: return@mapNotNull null
            cipher.accountId to folderId
        }
        .toSet()
    val folderItems = folders
        .filter { folder ->
            (folder.accountId to folder.id) in nonEmptyFolderKeys
        }
        .map { folder ->
            WearHomeItemSpec.Action(
                id = "preset.folder.${folder.accountId}.${folder.id}",
                title = TextHolder.Value(folder.name),
                icon = Icons.Outlined.Folder,
                destination = Destination.Folder(folder),
            )
        }

    val items = buildList {
        if (folderItems.size > 1) {
            this += WearHomeItemSpec.Section(
                id = "section.folders",
                title = TextHolder.Res(Res.string.folders),
            )
            addAll(folderItems)
        }

        this += WearHomeItemSpec.Section(
            id = "section.other",
            title = TextHolder.Res(Res.string.home_other_label),
        )
        this += WearHomeItemSpec.Action(
            id = "home",
            title = TextHolder.Res(Res.string.home_vault_label),
            icon = Icons.Outlined.Home,
            destination = Destination.Vault,
        )
        this += WearHomeItemSpec.Action(
            id = "send",
            title = TextHolder.Res(Res.string.home_send_label),
            icon = Icons.AutoMirrored.Outlined.Send,
            destination = Destination.Sends,
        )
        this += WearHomeItemSpec.Action(
            id = "generator",
            title = TextHolder.Res(Res.string.home_generator_label),
            icon = Icons.Outlined.Password,
            destination = Destination.Generator,
        )
        this += WearHomeItemSpec.Action(
            id = "settings",
            title = TextHolder.Res(Res.string.home_settings_label),
            icon = Icons.Outlined.Settings,
            destination = Destination.Settings,
        )
    }
    return WearHomeContentSpec(
        headerItem = WearHomeItemSpec.Action(
            id = "preset.favorites",
            title = TextHolder.Res(Res.string.home_favorites_label),
            icon = Icons.Outlined.Star,
            destination = Destination.Favorites,
        ),
        items = items,
    )
}
