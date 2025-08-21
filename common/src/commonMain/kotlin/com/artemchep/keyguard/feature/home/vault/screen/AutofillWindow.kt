package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.home.vault.component.VaultListItem
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.LeMOdelBottomSheet
import com.artemchep.keyguard.ui.Placeholder
import com.artemchep.keyguard.ui.ProvideScaffoldLocalValues
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.skeleton.SkeletonItemAvatar
import com.artemchep.keyguard.ui.skeleton.SkeletonSegmented
import com.artemchep.keyguard.ui.skeleton.skeletonItemsPlacer
import com.artemchep.keyguard.ui.tabs.SegmentedButtonGroup
import com.artemchep.keyguard.ui.theme.Dimens
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.compose.resources.stringResource

@Composable
fun RecentsButton(
    onValueChange: ((DSecret) -> Unit)? = null,
) {
    var isAutofillWindowShowing by remember {
        mutableStateOf(false)
    }

    val enabled = onValueChange != null
    if (!enabled) {
        // We can not autofill disabled text fields and we can not
        // tease user with it.
        isAutofillWindowShowing = false
    }

    IconButton(
        enabled = onValueChange != null,
        onClick = {
            isAutofillWindowShowing = !isAutofillWindowShowing
        },
    ) {
        IconBox(main = Icons.Outlined.History)
    }
    // Inject the dropdown popup to the bottom of the
    // content.
    val onDismissRequest = {
        isAutofillWindowShowing = false
    }
    LeMOdelBottomSheet(
        visible = isAutofillWindowShowing,
        onDismissRequest = onDismissRequest,
    ) { contentPadding ->
        Column(
            modifier = Modifier,
        ) {
            RecentsWindow(
                contentPadding = contentPadding,
                onComplete = { password ->
                    if (onValueChange != null && password != null) {
                        onValueChange(password)
                    }
                    // Hide the dropdown window on click on one
                    // of the buttons.
                    onDismissRequest()
                },
            )
        }
    }
}

@Composable
fun RecentsButtonRaw(
    visible: State<Boolean>,
    onDismissRequest: () -> Unit,
    onValueChange: ((DSecret) -> Unit)? = null,
) {
    LeMOdelBottomSheet(
        visible = visible.value,
        onDismissRequest = onDismissRequest,
    ) { contentPadding ->
        Column(
            modifier = Modifier,
        ) {
            ProvideScaffoldLocalValues(
                expressive = true,
            ) {
                RecentsWindow(
                    contentPadding = contentPadding,
                    onComplete = { cipher ->
                        if (onValueChange != null) {
                            onValueChange(cipher)
                        }
                        // Hide the dropdown window on click on one
                        // of the buttons.
                        onDismissRequest()
                    },
                )
            }
        }
    }
}

@Composable
fun ColumnScope.RecentsWindow(
    contentPadding: PaddingValues,
    onComplete: (DSecret) -> Unit,
) {
    val state = vaultRecentScreenState(
        highlightBackgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
        highlightContentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    )
    state.fold(
        ifLoading = {
            Column(
                modifier = Modifier
                    .padding(contentPadding),
            ) {
                RecentsWindowSkeleton()
            }
        },
        ifOk = { state ->
            RecentsWindowOk(
                contentPadding = contentPadding,
                state = state,
                onComplete = onComplete,
            )
        },
    )
}

@Composable
fun ColumnScope.RecentsWindowSkeleton(
) {
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    SkeletonSegmented(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    skeletonItemsPlacer { _, shapeState ->
        SkeletonItem(
            avatar = SkeletonItemAvatar.LARGE,
            shapeState = shapeState,
        )
    }
}

@Composable
fun ColumnScope.RecentsWindowOk(
    contentPadding: PaddingValues,
    state: VaultRecentState,
    onComplete: (DSecret) -> Unit,
) {
    val selectCipherFlow = state.sideEffects.selectCipherFlow
    LaunchedEffect(selectCipherFlow) {
        selectCipherFlow
            .onEach { cipher ->
                onComplete(cipher)
            }
            .launchIn(this)
    }

    val list by state.recent.collectAsState()
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        item("header") {
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )

            val selectedTab = state.selectedTab.collectAsState()
            val updatedOnSelectTab by rememberUpdatedState(state.onSelectTab)
            SegmentedButtonGroup(
                tabState = selectedTab,
                tabs = state.tabs,
                onClick = { tab ->
                    updatedOnSelectTab?.invoke(tab)
                },
                modifier = Modifier
                    .padding(horizontal = Dimens.contentPadding),
                weight = 1f,
            )

            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
        }

        if (list.isEmpty()) {
            item("header.empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Placeholder(
                        icon = Icons.Outlined.HistoryToggleOff,
                        title = stringResource(Res.string.ciphers_history_empty),
                    )
                }
            }
        }
        items(list, key = { it.id }) { item ->
            VaultListItem(
                modifier = Modifier,
                item = item,
            )
        }

        item("footer") {
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
        }
    }
}
