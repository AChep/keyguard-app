package com.artemchep.keyguard.feature.datasafety

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.LargeSection
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource

@Composable
fun dataSafetyItems(): List<DataSafetyItem> = dataSafetyCatalog(
    text = { stringResource(it) },
    format = { resource, argument -> stringResource(resource, argument) },
    dividerHorizontalPadding = Dimens.textHorizontalPadding,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSafetyScreen() {
    val scrollBehavior = ToolbarBehavior.behavior()
    val items = dataSafetyItems()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.datasafety_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        DataSafetyScreenContent(items)
    }
}

private fun LazyListScope.DataSafetyScreenContent(
    items: List<DataSafetyItem>,
) {
    items(
        items = items,
        key = { it.key },
    ) { item ->
        DataSafetyScreenItem(item = item)
    }
}

@Composable
private fun DataSafetyScreenItem(
    item: DataSafetyItem,
) {
    when (item) {
        is DataSafetyItem.Divider -> {
            HorizontalDivider(
                modifier = Modifier
                    .padding(vertical = item.verticalPadding)
                    .padding(horizontal = item.horizontalPadding),
            )
        }

        is DataSafetyItem.LargeSection -> {
            LargeSection(
                text = item.text,
            )
        }

        is DataSafetyItem.LearnMore -> {
            val navigationController by rememberUpdatedState(LocalNavigationController.current)
            TextButton(
                modifier = Modifier
                    .padding(
                        vertical = 4.dp,
                        horizontal = Dimens.buttonHorizontalPadding,
                    ),
                onClick = {
                    val intent = NavigationIntent.NavigateToBrowser(
                        url = item.url,
                    )
                    navigationController.queue(intent)
                },
            ) {
                Text(
                    text = stringResource(Res.string.learn_more),
                )
            }
        }

        is DataSafetyItem.Row -> {
            DataSafetySecondaryText(
                enabled = item.secondary,
            ) {
                TwoColumnRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    title = item.title,
                    value = item.value,
                )
            }
        }

        is DataSafetyItem.Section -> {
            Section(
                text = item.text,
            )
        }

        is DataSafetyItem.Spacer -> {
            Spacer(
                modifier = Modifier
                    .height(item.height),
            )
        }

        is DataSafetyItem.Text -> {
            DataSafetySecondaryText(
                enabled = item.secondary,
            ) {
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    text = item.text,
                )
            }
        }
    }
}

@Composable
private fun DataSafetySecondaryText(
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }

    val secondaryTextStyle = LocalTextStyle.current
        .merge(MaterialTheme.typography.bodyMedium)
        .copy(
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    CompositionLocalProvider(
        LocalTextStyle provides secondaryTextStyle,
        content = content,
    )
}

@Composable
private fun TwoColumnRow(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 120.dp),
            text = title,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 120.dp),
            text = value,
        )
    }
}
