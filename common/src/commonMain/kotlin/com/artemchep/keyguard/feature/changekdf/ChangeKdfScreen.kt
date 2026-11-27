package com.artemchep.keyguard.feature.changekdf

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.changepassword.ChangePasswordFab
import com.artemchep.keyguard.feature.changepassword.rememberChangePasswordFabState
import com.artemchep.keyguard.feature.masterkdf.MasterKdfForm
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun ChangeKdfScreen() {
    val loadableState = changeKdfState()
    loadableState.fold(
        ifLoading = {
            ChangeKdfScaffoldSkeleton()
        },
        ifOk = { state ->
            ChangeKdfScreen(
                state = state,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeKdfScaffoldSkeleton() {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            ChangeKdfToolbar(
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        changeKdfContentSkeleton()
    }
}

private fun LazyListScope.changeKdfContentSkeleton() {
    item("password.skeleton") {
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.horizontalPadding),
        )
    }
    item("password.kdf.spacer.skeleton") {
        Spacer(Modifier.height(16.dp))
    }
    item("kdf.note.skeleton") {
        SkeletonText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            emphasis = MediumEmphasisAlpha,
        )
    }
    repeat(MASTER_KDF_FIELD_COUNT) { index ->
        item("kdf.field.$index.skeleton") {
            SkeletonTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.horizontalPadding)
                    .padding(top = 8.dp),
            )
        }
    }
}

private const val MASTER_KDF_FIELD_COUNT = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChangeKdfToolbar(
    scrollBehavior: TopAppBarScrollBehavior,
) {
    LargeToolbar(
        title = {
            Text(
                text = stringResource(Res.string.masterkdf_title),
            )
        },
        navigationIcon = {
            NavigationIcon()
        },
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeKdfScreen(
    state: ChangeKdfState,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            ChangeKdfToolbar(
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = rememberChangePasswordFabState(
            onConfirm = state.onConfirm,
        ),
        floatingActionButton = {
            ChangePasswordFab(
                isLoading = state.isLoading,
                text = stringResource(Res.string.save),
            )
        },
    ) {
        changeKdfContent(
            state = state,
        )
    }
}

private fun LazyListScope.changeKdfContent(
    state: ChangeKdfState,
) {
    item("password") {
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            value = state.password,
            label = stringResource(Res.string.current_password),
        )
    }
    item("password.kdf.spacer") {
        Spacer(Modifier.height(16.dp))
    }
    item("kdf") {
        MasterKdfForm(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            state = state.kdf,
        )
    }
}
