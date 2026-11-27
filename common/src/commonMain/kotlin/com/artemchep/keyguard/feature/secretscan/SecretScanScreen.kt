package com.artemchep.keyguard.feature.secretscan

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.secretscan.SecretScanKind
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AnimatedTotalCounterBadge
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val SKELETON_ITEM_COUNT = 3

@Composable
fun SecretScanScreen(
    kind: SecretScanKind,
) {
    val loadableState = produceSecretScanState(kind)
    val scrollBehavior = ToolbarBehavior.behavior()
    when (loadableState) {
        is Loadable.Ok -> SecretScanScreenContent(
            scrollBehavior = scrollBehavior,
            state = loadableState.value,
        )

        is Loadable.Loading -> SecretScanScreenSkeleton(
            kind = kind,
            scrollBehavior = scrollBehavior,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretScanScreenSkeleton(
    kind: SecretScanKind,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            SecretScanToolbar(
                kind = kind,
                scrollBehavior = scrollBehavior,
                onRescan = null,
                onAddFolder = null,
            )
        },
    ) {
        repeat(SKELETON_ITEM_COUNT) {
            SkeletonItem()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretScanToolbar(
    kind: SecretScanKind,
    scrollBehavior: TopAppBarScrollBehavior,
    onRescan: (() -> Unit)?,
    onAddFolder: (() -> Unit)?,
) {
    LargeToolbar(
        title = {
            Text(
                text = stringResource(kind.ui.title),
            )
        },
        navigationIcon = {
            NavigationIcon()
        },
        actions = {
            IconButton(
                enabled = onAddFolder != null,
                onClick = {
                    onAddFolder?.invoke()
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.CreateNewFolder,
                    contentDescription = stringResource(Res.string.secret_scan_add_folder),
                )
            }
            // Shown after the first scan.
            if (onRescan != null) {
                IconButton(
                    onClick = onRescan,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(Res.string.secret_scan_rescan),
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretScanScreenContent(
    scrollBehavior: TopAppBarScrollBehavior,
    state: SecretScanState,
) {
    FilePickerEffect(state.filePickerIntentFlow)
    // Import selected while a row is checked, otherwise Scan.
    val importMode = state.selectedCount > 0 || state.importing
    val fabState = when {
        importMode -> FabState(
            onClick = state.onImport,
            model = null,
        )

        state.phase is SecretScanState.Phase.Scanning -> null
        else -> FabState(
            onClick = state.onScan,
            model = null,
        )
    }
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            SecretScanToolbar(
                kind = state.kind,
                scrollBehavior = scrollBehavior,
                // Shown after the first scan.
                onRescan = state.onScan
                    .takeUnless { state.phase is SecretScanState.Phase.Idle },
                onAddFolder = state.onAddFolder,
            )
        },
        floatingActionState = rememberUpdatedState(newValue = fabState),
        floatingActionButton = {
            SecretScanFab(
                importMode = importMode,
                importing = state.importing,
                selectedCount = state.selectedCount,
            )
        },
    ) {
        secretScanContent(
            state = state,
        )
    }
}

/** Import selected, with the count, while [importMode]; Scan otherwise. */
@Composable
private fun FabScope.SecretScanFab(
    importMode: Boolean,
    importing: Boolean,
    selectedCount: Int,
) {
    DefaultFab(
        icon = {
            Crossfade(
                modifier = Modifier
                    .size(24.dp),
                targetState = importing,
            ) { busy ->
                when {
                    busy -> KeyguardLoadingIndicator()
                    importMode -> BadgedBox(
                        badge = {
                            AnimatedTotalCounterBadge(
                                count = selectedCount,
                            )
                        },
                    ) {
                        Icon(Icons.Outlined.SystemUpdateAlt, null)
                    }

                    else -> Icon(Icons.Outlined.Search, null)
                }
            }
        },
        text = {
            val textRes = if (importMode) {
                Res.string.secret_scan_import_button
            } else {
                Res.string.secret_scan_scan
            }
            Text(
                text = stringResource(textRes),
            )
        },
    )
}

private fun LazyListScope.secretScanContent(
    state: SecretScanState,
) {
    if (!state.defaultRootsAvailable) {
        item("flatpak") {
            FlatSimpleNote(
                modifier = Modifier
                    .padding(vertical = 4.dp),
                type = SimpleNote.Type.INFO,
                text = stringResource(Res.string.secret_scan_flatpak_note),
            )
        }
    }
    val phase = state.phase
    if (phase is SecretScanState.Phase.Idle) {
        item("intro") {
            SecretScanIntro(
                kind = state.kind,
                roots = state.roots,
            )
        }
        return
    }
    item("progress") {
        SecretScanProgress(
            scanning = phase is SecretScanState.Phase.Scanning,
            checkedFlow = state.checkedFlow,
        )
    }
    if (phase is SecretScanState.Phase.Done) {
        items(
            items = state.items,
            key = { it.key },
        ) { item ->
            SecretScanItemRow(
                item = item,
                onSelectedChange = state.onSelectedChange,
            )
        }
        if (state.items.isEmpty()) {
            item("empty") {
                SecretScanEmpty(
                    scannedRoots = phase.scannedRoots,
                )
            }
        }
    }
}

@Composable
private fun SecretScanIntro(
    kind: SecretScanKind,
    roots: List<String>,
) {
    val ui = kind.ui
    val paragraphs = buildList {
        add(stringResource(ui.intro))
        if (roots.isNotEmpty()) {
            add(stringResource(Res.string.secret_scan_intro_where, roots.joinToString()))
        }
        add(stringResource(ui.introImport))
    }
    Column(
        modifier = Modifier
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paragraphs.forEach { text ->
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.textHorizontalPadding),
                text = text,
            )
        }
    }
}

@Composable
private fun SecretScanProgress(
    scanning: Boolean,
    checkedFlow: StateFlow<Int>,
) {
    val checked by checkedFlow.collectAsState()
    Row(
        modifier = Modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
                vertical = 8.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (scanning) {
            KeyguardLoadingIndicator(
                modifier = Modifier
                    .size(20.dp),
            )
        }
        Text(
            text = pluralStringResource(
                Res.plurals.secret_scan_checked_plural,
                checked,
                checked,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SecretScanEmpty(
    scannedRoots: List<String>,
) {
    Column {
        EmptyView(
            text = {
                Text(
                    text = stringResource(Res.string.secret_scan_empty_title),
                )
            },
        )
        if (scannedRoots.isNotEmpty()) {
            Text(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.textHorizontalPadding,
                        vertical = 4.dp,
                    ),
                text = stringResource(Res.string.secret_scan_scanned_folders),
                style = MaterialTheme.typography.labelLarge,
            )
            scannedRoots.forEach { root ->
                Text(
                    modifier = Modifier
                        .padding(
                            horizontal = Dimens.textHorizontalPadding,
                        ),
                    text = root,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
