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
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasBrowser
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

private const val DATA_SAFETY_LEARN_MORE_URL = "https://bitwarden.com/help/what-encryption-is-used/"

@Composable
fun dataSafetyItems(): List<DataSafetyItem> {
    val labelAppPassword = stringResource(Res.string.app_password)
    val labelHash = stringResource(Res.string.encryption_hash)
    val labelSalt = stringResource(Res.string.encryption_salt)
    val labelKey = stringResource(Res.string.encryption_key)
    return listOfNotNull(
        DataSafetyItem.LargeSection(
            key = "local.section",
            text = stringResource(Res.string.datasafety_local_section),
        ),
        DataSafetyItem.Text(
            key = "local.text",
            text = stringResource(Res.string.datasafety_local_text),
        ),
        DataSafetyItem.Spacer(
            key = "local.text.spacer",
            height = 16.dp,
        ),
        DataSafetyItem.Section(
            key = "local.downloads.section",
            text = stringResource(Res.string.datasafety_local_downloads_section),
        ),
        DataSafetyItem.Row(
            key = "local.downloads.encryption",
            title = stringResource(Res.string.encryption),
            value = stringResource(Res.string.none),
        ),
        DataSafetyItem.Spacer(
            key = "local.downloads.spacer",
            height = 16.dp,
        ),
        DataSafetyItem.Section(
            key = "local.settings.section",
            text = stringResource(Res.string.datasafety_local_settings_section),
        ),
        DataSafetyItem.Row(
            key = "local.settings.encryption",
            title = stringResource(Res.string.encryption),
            value = "256-bit AES",
        ),
        DataSafetyItem.Spacer(
            key = "local.settings.encryption.spacer",
            height = 16.dp,
        ),
        DataSafetyItem.Text(
            key = "local.settings.note",
            text = stringResource(Res.string.datasafety_local_settings_note),
            secondary = true,
        ),
        DataSafetyItem.Spacer(
            key = "local.settings.note.spacer",
            height = 16.dp,
        ),
        DataSafetyItem.Section(
            key = "local.vault.section",
            text = stringResource(Res.string.datasafety_local_vault_section),
        ),
        DataSafetyItem.Row(
            key = "local.vault.encryption",
            title = stringResource(Res.string.encryption),
            value = stringResource(Res.string.encryption_algorithm_256bit_aes),
        ),
        DataSafetyItem.Spacer(
            key = "local.vault.encryption.spacer",
            height = 16.dp,
        ),
        DataSafetyItem.Text(
            key = "local.vault.algorithm.intro",
            text = stringResource(Res.string.datasafety_local_encryption_algorithm_intro),
            secondary = true,
        ),
        DataSafetyItem.Spacer(
            key = "local.vault.algorithm.intro.spacer",
            height = 16.dp,
        ),
        DataSafetyItem.Row(
            key = "local.vault.algorithm.salt",
            title = labelSalt,
            value = stringResource(Res.string.encryption_random_bits_data, 64),
            secondary = true,
        ),
        DataSafetyItem.Divider(
            key = "local.vault.algorithm.salt.divider",
            verticalPadding = 8.dp,
            horizontalPadding = Dimens.textHorizontalPadding,
        ),
        DataSafetyItem.Row(
            key = "local.vault.algorithm.hash",
            title = labelHash,
            value = "Argon2id($labelAppPassword, $labelSalt)",
            secondary = true,
        ),
        DataSafetyItem.Divider(
            key = "local.vault.algorithm.hash.divider",
            verticalPadding = 8.dp,
            horizontalPadding = Dimens.textHorizontalPadding,
        ),
        DataSafetyItem.Row(
            key = "local.vault.algorithm.key",
            title = labelKey,
            value = "Argon2id($labelAppPassword, $labelHash)",
            secondary = true,
        ),
        DataSafetyItem.Spacer(
            key = "local.vault.algorithm.outro.spacer",
            height = 16.dp,
        ),
        DataSafetyItem.Text(
            key = "local.vault.algorithm.outro",
            text = stringResource(Res.string.datasafety_local_encryption_algorithm_outro),
            secondary = true,
        ),
        DataSafetyItem.Divider(
            key = "local.vault.unlocking.divider",
            verticalPadding = 16.dp,
        ),
        DataSafetyItem.Text(
            key = "local.vault.unlocking",
            text = stringResource(
                Res.string.datasafety_local_unlocking_vault,
                labelKey,
            ),
        ),
        DataSafetyItem.LargeSection(
            key = "remote.section",
            text = stringResource(Res.string.datasafety_remote_section),
        ),
        DataSafetyItem.Text(
            key = "remote.text",
            text = stringResource(Res.string.datasafety_remote_text),
        ),
        DataSafetyItem.LearnMore(
            key = "remote.learn_more",
            url = DATA_SAFETY_LEARN_MORE_URL,
        ).takeIf {
            CurrentPlatform.hasBrowser()
        },
    )
}

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
