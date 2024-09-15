package com.artemchep.keyguard.feature.feedback

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun FeedbackScreen() {
    val loadableState = produceFeedbackScreenState()
    FeedbackContent(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedbackContent(
    loadableState: Loadable<FeedbackState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.contactus_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val fabOnClick = loadableState.getOrNull()?.onSendClick
            val fabState = if (fabOnClick != null) {
                FabState(
                    onClick = fabOnClick,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Icon(Icons.AutoMirrored.Outlined.Send, null)
                },
                text = {
                    Text(
                        text = stringResource(Res.string.send),
                    )
                },
            )
        },
    ) {
        val contentModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding)
        loadableState.fold(
            ifLoading = {
                SkeletonTextField(
                    modifier = contentModifier,
                )
            },
            ifOk = { state ->
                FlatTextField(
                    modifier = contentModifier,
                    label = stringResource(Res.string.contactus_message_label),
                    value = state.message,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = true,
                        keyboardType = KeyboardType.Text,
                    ),
                )
            },
        )
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.contactus_english_note),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = stringResource(Res.string.contactus_thanks_note),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
        )
    }
}
