package com.artemchep.keyguard.feature.websiteleak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.ui.icons.FaviconIcon
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.FlatTextFieldBadge
import com.artemchep.keyguard.ui.HtmlText
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.poweredby.PoweredByHaveibeenpwned
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

@Composable
fun WebsiteLeakScreen(
    args: WebsiteLeakRoute.Args,
) {
    val loadableState = produceWebsiteLeakState(
        args = args,
    )
    Dialog(
        icon = icon(Icons.AutoMirrored.Outlined.FactCheck),
        title = {
            Text(stringResource(Res.string.emailleak_title))
        },
        content = {
            Column {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding),
                    text = stringResource(Res.string.emailleak_note),
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
    state: WebsiteLeakState,
) {
    val leaks = state.content.breaches
    if (leaks.isNotEmpty()) {
        FlatSimpleNote(
            type = SimpleNote.Type.WARNING,
            title = stringResource(Res.string.emailleak_breach_found_title),
        )
        Section(
            text = stringResource(Res.string.emailleak_breach_section),
        )
    } else {
        FlatSimpleNote(
            type = SimpleNote.Type.OK,
            title = stringResource(Res.string.emailleak_breach_not_found_title),
        )
    }
    leaks.forEachIndexed { index, item ->
        if (index > 0) {
            HorizontalDivider(
                modifier = Modifier
                    .padding(
                        vertical = 16.dp,
                    ),
            )
        }

        BreachItem(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.horizontalPadding,
                ),
            item = item,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreachItem(
    modifier: Modifier = Modifier,
    item: WebsiteLeakState.Breach,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FaviconIcon(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape),
                imageModel = { item.icon },
            )
            Spacer(
                modifier = Modifier
                    .width(16.dp),
            )
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = item.domain,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        if (item.dataClasses.isNotEmpty()) {
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.dataClasses.forEach { dataClass ->
                    FlatTextFieldBadge(
                        backgroundColor = MaterialTheme.colorScheme.infoContainer,
                        text = dataClass,
                    )
                }
            }
        }
        Column {
            if (item.count != null) {
                val numberFormatter: NumberFormatter by rememberInstance()
                Text(
                    text = pluralStringResource(
                        Res.plurals.emailleak_breach_accounts_count_plural,
                        item.count,
                        numberFormatter.formatNumber(item.count.toInt()),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Black,
                )
            }
            if (item.occurredAt != null) {
                Text(
                    text = stringResource(
                        Res.string.emailleak_breach_occurred_at,
                        item.occurredAt,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
            if (item.reportedAt != null) {
                Text(
                    text = stringResource(
                        Res.string.emailleak_breach_reported_at,
                        item.reportedAt,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
        }
        ProvideTextStyle(MaterialTheme.typography.bodySmall) {
            HtmlText(
                html = item.description,
            )
        }
    }
}
