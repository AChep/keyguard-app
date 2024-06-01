package com.artemchep.keyguard.feature.passwordleak

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.poweredby.PoweredByHaveibeenpwned
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

@Composable
fun PasswordLeakScreen(
    args: PasswordLeakRoute.Args,
) {
    val loadableState = producePasswordLeakState(
        args = args,
    )
    Dialog(
        icon = icon(Icons.AutoMirrored.Outlined.FactCheck),
        title = {
            Text(stringResource(Res.string.passwordleak_title))
        },
        content = {
            Column {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    text = stringResource(Res.string.passwordleak_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )

                when (loadableState) {
                    is Loadable.Loading -> {
                        ContentSkeleton()
                    }

                    is Loadable.Ok -> {
                        Content(
                            state = loadableState.value,
                        )
                    }
                }

                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )

                PoweredByHaveibeenpwned(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding)
                        .fillMaxWidth(),
                )
            }
        },
        contentScrollable = true,
        actions = {
            val updatedOnClose by rememberUpdatedState(loadableState.getOrNull()?.onClose)
            TextButton(
                enabled = updatedOnClose != null,
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
private fun ColumnScope.ContentSkeleton() {
    FlatItem(
        title = {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.8f),
            )
        },
        text = {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.6f),
            )
        },
        enabled = true,
        elevation = 1.dp,
    )
}

@Composable
private fun ColumnScope.Content(
    state: PasswordLeakState,
) {
    val leaks = state.content.getOrNull()?.occurrences
    if (leaks == null) {
        FlatSimpleNote(
            type = SimpleNote.Type.WARNING,
            text = stringResource(Res.string.passwordleak_failed_to_load_status_text),
        )
        return
    }

    if (leaks > 0) {
        FlatSimpleNote(
            type = SimpleNote.Type.WARNING,
            title = stringResource(Res.string.passwordleak_occurrences_found_title),
            text = stringResource(Res.string.passwordleak_occurrences_found_text),
        )
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        val numberFormatter: NumberFormatter by rememberInstance()
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = pluralStringResource(
                Res.plurals.passwordleak_occurrences_count_plural,
                leaks,
                numberFormatter.formatNumber(leaks),
            ),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
        )
    } else {
        FlatSimpleNote(
            type = SimpleNote.Type.OK,
            title = stringResource(Res.string.passwordleak_occurrences_not_found_title),
        )
    }
}
