package com.artemchep.keyguard.feature.home.vault.organization

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun OrganizationScreen(
    args: OrganizationRoute.Args,
) {
    val state = organizationScreenState(
        args = args,
    )
    Dialog(
        title = {
            Text("Collection info")
        },
        content = {
            state.content.fold(
                ifLoading = {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(16.dp),
                    )
                },
                ifOk = { contentOrNull ->
                    if (contentOrNull != null) {
                        OrganizationScreenContent(
                            content = contentOrNull,
                        )
                    }
                },
            )
        },
        actions = {
            val updatedOnClose by rememberUpdatedState(state.onClose)
            TextButton(
                enabled = state.onClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@Composable
fun OrganizationScreenContent(
    content: OrganizationState.Content,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
    ) {
        ExpandedIfNotEmpty(
            valueOrNull = Unit.takeIf { content.config.selfHost },
        ) {
            Text(
                modifier = Modifier
                    .padding(vertical = 2.dp),
                text = "Self-hosted",
                style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
        }
    }
}
