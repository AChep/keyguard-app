package com.artemchep.keyguard.feature.applink.connection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.applink.AppLinkInfoSection
import com.artemchep.keyguard.feature.applink.appLinkRelayInfo
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardNetworkFields
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_connection_title
import com.artemchep.keyguard.res.save
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

/** The relay this device reaches its linked apps through; the same for every link. */
@Composable
fun AppLinkConnectionScreen() {
    val loadableState = produceAppLinkConnectionScreenState()
    AppLinkConnectionContent(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppLinkConnectionContent(
    loadableState: Loadable<AppLinkConnectionState>,
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
                        text = stringResource(Res.string.app_link_connection_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val onSave = loadableState.getOrNull()?.onSave
            val fabState = onSave?.let {
                FabState(
                    onClick = it,
                    model = null,
                )
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Save,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.string.save),
                    )
                },
            )
        },
    ) {
        loadableState.fold(
            ifLoading = {
                repeat(SKELETON_ITEMS) {
                    SkeletonItem()
                }
            },
            ifOk = { state ->
                AppLinkConnectionBody(
                    state = state,
                )
            },
        )
    }
}

private const val SKELETON_ITEMS = 3

@Composable
private fun AppLinkConnectionBody(
    state: AppLinkConnectionState,
) {
    KeyguardNetworkFields(
        network = state.network,
    )
    AppLinkInfoSection(
        notes = appLinkRelayInfo,
    )
}
