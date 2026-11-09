package com.artemchep.keyguard.feature.auth.keyguard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingStepContent
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.done
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

/**
 * The pairing screen: the pairing in the way the user picked, then that the devices
 * are linked.
 *
 * @param addAnotherTitle pairs with one more device, once the devices are linked.
 * @param onDone closes the screen once the devices are linked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyguardPairContent(
    loadableState: Loadable<KeyguardPairState>,
    title: String,
    premiumNote: String,
    addAnotherTitle: String,
    onDone: () -> Unit,
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
                        text = title,
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
                SkeletonText(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding)
                        .fillMaxWidth(fraction = 0.6f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            ifOk = { state ->
                PairingStepContent(
                    step = state.step,
                    idle = {
                        if (state.premiumRequired) {
                            FlatSimpleNote(
                                type = SimpleNote.Type.WARNING,
                                text = premiumNote,
                            )
                        }
                    },
                    doneActions = {
                        KeyguardPairDone(
                            addAnotherTitle = addAnotherTitle,
                            onAddAnother = state.onAddAnother,
                            onDone = onDone,
                        )
                    },
                )
                Spacer(Modifier.height(24.dp))
                KeyguardPairingAsCaption(
                    text = state.pairingAsText,
                )
            },
        )
    }
}

/**
 * Who this device pairs as. The name and the relay belong to this device, so the
 * linked devices screen sets them, not each pairing.
 */
@Composable
private fun KeyguardPairingAsCaption(
    text: String,
) {
    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
        textAlign = TextAlign.Center,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun KeyguardPairDone(
    addAnotherTitle: String,
    onAddAnother: (() -> Unit)?,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (onAddAnother != null) {
            FilledTonalButton(
                shapes = ButtonDefaults.shapes(),
                onClick = onAddAnother,
            ) {
                Text(
                    text = addAnotherTitle,
                )
            }
        }
        Button(
            shapes = ButtonDefaults.shapes(),
            onClick = onDone,
        ) {
            Text(
                text = stringResource(Res.string.done),
            )
        }
    }
}
