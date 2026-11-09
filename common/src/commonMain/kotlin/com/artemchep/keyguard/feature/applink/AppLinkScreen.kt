@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.artemchep.keyguard.feature.applink

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ForwardToInbox
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesHeader
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesLeaveItem
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesList
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesRemovedNote
import com.artemchep.keyguard.feature.keyguardsync.devices.KeyguardDevicesState
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_link_device_text
import com.artemchep.keyguard.res.app_link_link_device_title
import com.artemchep.keyguard.res.app_link_other_devices_section_title
import com.artemchep.keyguard.res.app_link_premium_note
import com.artemchep.keyguard.res.app_link_sync_section_title
import com.artemchep.keyguard.res.pref_item_app_link_title
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.KeyguardDropdownMenu
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.SwitchExpressive
import com.artemchep.keyguard.ui.icons.ShapedIcon
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun AppLinkScreen() {
    val loadableState = produceAppLinkScreenState()
    AppLinkContent(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLinkContent(
    loadableState: Loadable<AppLinkState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.pref_item_app_link_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        loadableState.fold(
            ifLoading = {
                repeat(2) {
                    SkeletonItem()
                }
            },
            ifOk = { state ->
                when (val content = state.content) {
                    is AppLinkState.Content.Unlinked -> AppLinkUnlinked(
                        state = state,
                        content = content,
                    )

                    AppLinkState.Content.Linked -> AppLinkLinked(
                        state = state,
                    )
                }
            },
        )
    }
}

/**
 * Not linked yet: what linking does and how to start, then this device and what it
 * syncs, which the user sets before the first pairing.
 */
@Composable
private fun AppLinkUnlinked(
    state: AppLinkState,
    content: AppLinkState.Content.Unlinked,
) {
    AppLinkUnlinkedHero()
    Spacer(Modifier.height(24.dp))
    AppLinkPremiumNote(
        premiumRequired = state.premiumRequired,
    )
    AppLinkPairActions(
        linkActions = content.linkActions,
    )
    Spacer(Modifier.height(8.dp))
    AppLinkThisDeviceSection(
        thisDevice = state.thisDevice,
        fingerprint = null,
    )
    AppLinkCategories(
        categories = state.categories,
    )
    AppLinkHowItWorks()
}

/** Linked: the devices, this one, what it syncs, and how to leave. */
@Composable
private fun AppLinkLinked(
    state: AppLinkState,
) {
    val devicesState = produceAppLinkDevicesScreenState()
    devicesState.fold(
        ifLoading = {
            repeat(2) {
                SkeletonItem()
            }
        },
        ifOk = { devices ->
            val thisDevice = devices.devices.firstOrNull { it.isThisDevice }
            val otherDevices = devices.devices.filterNot { it.isThisDevice }
            AppLinkMesh(
                thisPlatform = state.thisDevice.platform,
                peers = otherDevices,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                KeyguardDevicesHeader(
                    header = devices.header,
                )
            }
            Spacer(Modifier.height(16.dp))
            AppLinkPremiumNote(
                premiumRequired = state.premiumRequired,
            )
            KeyguardDevicesRemovedNote(
                state = devices,
            )
            AppLinkOtherDevices(
                devices = devices,
                otherDevices = otherDevices,
            )
            AppLinkThisDeviceSection(
                thisDevice = state.thisDevice,
                fingerprint = thisDevice?.fingerprint,
            )
            AppLinkCategories(
                categories = state.categories,
            )
            Section(
                expressive = true,
            )
            KeyguardDevicesLeaveItem(
                title = devices.texts.leaveTitle,
                text = devices.texts.leaveText,
                onClick = devices.onLeave,
            )
            AppLinkHowItWorks()
        },
    )
}

@Composable
private fun AppLinkOtherDevices(
    devices: KeyguardDevicesState,
    otherDevices: List<KeyguardDevicesState.Device>,
) {
    if (otherDevices.isEmpty() && devices.addDeviceActions.isEmpty()) {
        return
    }
    Section(
        text = stringResource(Res.string.app_link_other_devices_section_title),
    )
    KeyguardDevicesList(
        devices = otherDevices,
        addDeviceTitle = devices.texts.addDeviceTitle,
        addDeviceActions = devices.addDeviceActions,
    )
}

/**
 * Links a device: a popup asks which of the two devices shows the code; which of the two
 * admits the other one is not the user's choice.
 */
@Composable
private fun AppLinkPairActions(
    linkActions: List<ContextItem>,
) {
    AppLinkPairCard(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .fillMaxWidth(),
        icon = Icons.Outlined.AddLink,
        shape = MaterialShapes.Cookie9Sided,
        title = stringResource(Res.string.app_link_link_device_title),
        text = stringResource(Res.string.app_link_link_device_text),
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        actions = linkActions,
    )
}

@Suppress("LongParameterList") // The looks of a card.
@Composable
private fun AppLinkPairCard(
    modifier: Modifier,
    icon: ImageVector,
    shape: RoundedPolygon,
    title: String,
    text: String,
    containerColor: Color,
    contentColor: Color,
    actions: List<ContextItem>,
) {
    val enabled = actions.isNotEmpty()
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize(),
            onClick = {
                expanded = true
            },
            enabled = enabled,
            shape = MaterialTheme.shapes.extraLarge,
            color = if (enabled) containerColor else containerColor.combineAlpha(DisabledEmphasisAlpha),
            contentColor = if (enabled) contentColor else contentColor.combineAlpha(DisabledEmphasisAlpha),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp),
            ) {
                ShapedIcon(
                    icon = icon,
                    shape = shape,
                    containerColor = contentColor.copy(alpha = PAIR_CARD_ICON_ALPHA),
                    contentColor = contentColor,
                    size = 48.dp,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
        }
        KeyguardDropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
            },
        ) {
            actions.forEach { action ->
                DropdownMenuItemFlat(
                    action = action,
                )
            }
        }
    }
}

private const val PAIR_CARD_ICON_ALPHA = 0.12f

@Composable
private fun AppLinkCategories(
    categories: List<AppLinkState.Category>,
) {
    Section(
        text = stringResource(Res.string.app_link_sync_section_title),
    )
    categories.forEachIndexed { index, category ->
        val onCheckedChange = category.onCheckedChange
        FlatItemSimpleExpressive(
            elevation = 1.dp,
            shapeState = getShapeState(
                list = categories,
                index = index,
                predicate = { _, _ -> true },
            ),
            leading = {
                Icon(
                    imageVector = category.category.icon(),
                    contentDescription = null,
                )
            },
            title = {
                Text(
                    text = textResource(category.title),
                )
            },
            trailing = {
                SwitchExpressive(
                    checked = category.checked,
                    onCheckedChange = null,
                )
            },
            onClick = onCheckedChange?.let { onChange ->
                // lambda
                {
                    onChange(!category.checked)
                }
            },
        )
    }
}

@Composable
private fun AppLinkPremiumNote(
    premiumRequired: Boolean,
) {
    if (!premiumRequired) {
        return
    }
    FlatSimpleNote(
        type = SimpleNote.Type.WARNING,
        text = stringResource(Res.string.app_link_premium_note),
    )
    Spacer(Modifier.height(8.dp))
}

private fun DAppLinkSyncCategory.icon() = when (this) {
    DAppLinkSyncCategory.EMAIL_FORWARDERS -> Icons.AutoMirrored.Outlined.ForwardToInbox
    DAppLinkSyncCategory.WORDLISTS -> Icons.Outlined.Book
    DAppLinkSyncCategory.URL_OVERRIDES -> Icons.Outlined.Link
}
