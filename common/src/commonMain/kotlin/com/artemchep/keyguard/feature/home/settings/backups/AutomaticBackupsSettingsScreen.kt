package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.RequestLazyListScrollOnRevision
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

@Composable
fun AutomaticBackupsSettingsScreen() {
    val dateFormatter by rememberInstance<DateFormatter>()
    val state = produceAutomaticBackupsSettingsScreenState()
    state.fold(
        ifLoading = {
            AutomaticBackupsSettingsLoadingContent()
        },
        ifOk = { okState ->
            FilePickerEffect(okState.filePickerIntentFlow)
            AutomaticBackupsSettingsContent(
                state = okState,
                dateFormatter = dateFormatter,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomaticBackupsSettingsLoadingContent() {
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
                        text = stringResource(Res.string.settings_automatic_backups_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        skeletonItems()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomaticBackupsSettingsContent(
    state: AutomaticBackupsSettingsState,
    dateFormatter: DateFormatter,
) {
    val setupSectionTitle =
        stringResource(Res.string.pref_section_automatic_backups_setup_title)
    val automationSectionTitle =
        stringResource(Res.string.pref_section_automatic_backups_automation_title)
    val managementSectionTitle =
        stringResource(Res.string.pref_section_automatic_backups_management_title)

    val scrollBehavior = ToolbarBehavior.behavior()
    val isSupported = CurrentPlatform is Platform.Desktop ||
            CurrentPlatform is Platform.Mobile.Android
    val components = LocalSettingPaneComponents.current

    val isBackupRunning = state.status.currentRun != null
    val fabState = when {
        !state.config.enabled && isSupported -> {
            FabState(
                onClick = state.onEnableClick.takeUnless { state.isTestingLocation },
                model = null,
            )
        }

        state.config.enabled && isSupported -> {
            FabState(
                onClick = state.onRunNow.takeIf {
                    state.config.canRun() && !isBackupRunning
                },
                model = state.status.currentRun,
            )
        }

        else -> null
    }

    val listState = remember {
        LazyListState(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
        )
    }

    RequestLazyListScrollOnRevision(
        listState = listState,
        revision = state.config.enabled.int,
    )

    val configExpanded = remember(state.config.enabled) {
        mutableStateOf(false)
    }
    val statusExpanded = remember(state.config.enabled) {
        mutableStateOf(false)
    }
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        floatingActionState = rememberUpdatedState(fabState),
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Crossfade(
                        targetState = state.isTestingLocation || isBackupRunning,
                    ) { isLoading ->
                        if (isLoading) {
                            KeyguardLoadingIndicator()
                        } else {
                            Icon(
                                imageVector = if (state.config.enabled) {
                                    Icons.Outlined.PlayArrow
                                } else {
                                    Icons.Outlined.Check
                                },
                                contentDescription = null,
                            )
                        }
                    }
                },
                text = {
                    Text(
                        text = if (state.config.enabled) {
                            stringResource(Res.string.pref_item_automatic_backups_run_now_title)
                        } else {
                            stringResource(Res.string.pref_item_automatic_backups_enable_button)
                        },
                    )
                },
            )
        },
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.settings_automatic_backups_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        listState = listState,
    ) {
        if (state.config.enabled) {
            AutomaticBackupsEnabledContent(
                state = state,
                dateFormatter = dateFormatter,
                configExpanded = configExpanded,
                statusExpanded = statusExpanded,
                automationSectionTitle = automationSectionTitle,
                managementSectionTitle = managementSectionTitle,
                components = components,
            )
        } else {
            AutomaticBackupsSetupContent(
                state = state,
                isSupported = isSupported,
                setupSectionTitle = setupSectionTitle,
                components = components,
            )
        }
    }
}
