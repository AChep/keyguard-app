package com.artemchep.keyguard.feature.gpgagent.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.gpgagent.keyserver.search.GpgKeyserverSearchRoute
import com.artemchep.keyguard.feature.home.settings.gpg.GpgSettingsRoute
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.navigationNextEntryOrNull
import com.artemchep.keyguard.feature.twopane.LocalHasDetailPane
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_keyserver_status_title
import com.artemchep.keyguard.res.gpg_tools_header_title
import com.artemchep.keyguard.res.gpg_tools_operation_decrypt_text
import com.artemchep.keyguard.res.gpg_tools_operation_encrypt_text
import com.artemchep.keyguard.res.gpg_tools_operation_sign_text
import com.artemchep.keyguard.res.gpg_tools_operation_verify_text
import com.artemchep.keyguard.res.pref_item_gpg_keyserver_search_text
import com.artemchep.keyguard.res.pref_item_gpg_keyserver_search_title
import com.artemchep.keyguard.res.settings_gpg_agent_header_title
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.grid.SimpleGridLayout
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.onSelectedContainer
import com.artemchep.keyguard.ui.theme.selectedContainer
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GpgToolsPickerScreen() {
    val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)
    GpgToolsPickerScreen(
        onOperationClick = { operation ->
            navigateTo(
                navigationController = updatedNavigationController,
                route = operation.route,
            )
        },
        onKeyserverSearchClick = {
            navigateTo(
                navigationController = updatedNavigationController,
                route = GpgKeyserverSearchRoute,
            )
        },
        onSettingsClick = {
            navigateTo(
                navigationController = updatedNavigationController,
                route = GpgSettingsRoute,
            )
        },
    )
}

private fun navigateTo(
    navigationController: NavigationController,
    route: Route,
) {
    val intent = NavigationIntent.Composite(
        listOf(
            NavigationIntent.PopById(GpgToolsRoute.ROUTER_NAME),
            NavigationIntent.NavigateToRoute(
                route = route,
            ),
        ),
    )
    navigationController.queue(intent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpgToolsPickerScreen(
    onOperationClick: (GpgToolsOperation) -> Unit,
    onKeyserverSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.gpg_tools_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    IconButton(
                        onClick = onSettingsClick,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(Res.string.settings_gpg_agent_header_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        item("operations") {
            SimpleGridLayout(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.contentPadding),
                mainAxisSpacing = 8.dp,
                crossAxisSpacing = 8.dp,
                minCellWidth = 188.dp,
            ) {
                GpgToolsOperation.entries.forEach { operation ->
                    GpgOperationCard(
                        operation = operation,
                        onClick = {
                            onOperationClick(operation)
                        },
                    )
                }
            }
        }
        item("keyserver_section") {
            Section(
                text = stringResource(Res.string.gpg_keyserver_status_title),
            )
        }
        item("keyserver_search") {
            GpgKeyserverSearchCard(
                onClick = onKeyserverSearchClick,
            )
        }
        item("bottom_spacer") {
            Spacer(
                modifier = Modifier.height(16.dp),
            )
        }
    }
}

@Composable
private fun GpgKeyserverSearchCard(
    onClick: () -> Unit,
) {
    GpgToolsCard(
        icon = Icons.Outlined.Public,
        title = stringResource(Res.string.pref_item_gpg_keyserver_search_title),
        text = stringResource(Res.string.pref_item_gpg_keyserver_search_text),
        route = GpgKeyserverSearchRoute,
        horizontalPadding = true,
        onClick = onClick,
    )
}

@Composable
private fun GpgOperationCard(
    operation: GpgToolsOperation,
    onClick: () -> Unit,
) {
    GpgToolsCard(
        icon = operation.icon,
        title = textResource(operation.title),
        text = stringResource(operation.summary),
        route = operation.route,
        onClick = onClick,
    )
}

@Composable
private fun GpgToolsCard(
    icon: ImageVector,
    title: String,
    text: String,
    route: Route,
    horizontalPadding: Boolean = false,
    onClick: () -> Unit,
) {
    val selected = LocalHasDetailPane.current &&
            navigationNextEntryOrNull()?.route === route
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.selectedContainer
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevationSemi(1.dp)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSelectedContainer
    } else {
        LocalContentColor.current
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (horizontalPadding) {
                    Modifier.padding(horizontal = Dimens.contentPadding)
                } else {
                    Modifier
                },
            )
            .heightIn(min = 136.dp)
            .clip(MaterialTheme.shapes.large)
            .background(backgroundColor)
            .clickable(role = Role.Button) {
                onClick()
            },
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                Avatar {
                    Icon(
                        modifier = Modifier
                            .align(Alignment.Center),
                        imageVector = icon,
                        contentDescription = null,
                    )
                }
                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.combineAlpha(MediumEmphasisAlpha),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val GpgToolsOperation.route
    get() = when (this) {
        GpgToolsOperation.ENCRYPT -> GpgToolsEncryptRoute
        GpgToolsOperation.DECRYPT -> GpgToolsDecryptRoute
        GpgToolsOperation.SIGN -> GpgToolsSignRoute
        GpgToolsOperation.VERIFY -> GpgToolsVerifyRoute
    }

private val GpgToolsOperation.summary
    get() = when (this) {
        GpgToolsOperation.ENCRYPT -> Res.string.gpg_tools_operation_encrypt_text
        GpgToolsOperation.DECRYPT -> Res.string.gpg_tools_operation_decrypt_text
        GpgToolsOperation.SIGN -> Res.string.gpg_tools_operation_sign_text
        GpgToolsOperation.VERIFY -> Res.string.gpg_tools_operation_verify_text
    }
