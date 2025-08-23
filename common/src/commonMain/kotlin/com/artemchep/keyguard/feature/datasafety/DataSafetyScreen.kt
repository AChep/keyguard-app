package com.artemchep.keyguard.feature.datasafety

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSafetyScreen() {
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
        DataSafetyScreenContent()
    }
}

@Composable
private fun ColumnScope.DataSafetyScreenContent() {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val secondaryTextStyle = LocalTextStyle.current
        .merge(MaterialTheme.typography.bodyMedium)
        .copy(
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )

    LargeSection(
        text = stringResource(Res.string.datasafety_local_section),
    )
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(Res.string.datasafety_local_text),
    )
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    Section(
        text = stringResource(Res.string.datasafety_local_downloads_section),
    )
    TwoColumnRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        title = stringResource(Res.string.encryption),
        value = stringResource(Res.string.none),
    )
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    Section(
        text = stringResource(Res.string.datasafety_local_settings_section),
    )
    TwoColumnRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        title = stringResource(Res.string.encryption),
        value = "256-bit AES",
    )
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    CompositionLocalProvider(
        LocalTextStyle provides secondaryTextStyle,
    ) {
        Column {
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.textHorizontalPadding),
                text = stringResource(Res.string.datasafety_local_settings_note),
            )
        }
    }
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    Section(
        text = stringResource(Res.string.datasafety_local_vault_section),
    )
    TwoColumnRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        title = stringResource(Res.string.encryption),
        value = stringResource(Res.string.encryption_algorithm_256bit_aes),
    )
    Spacer(
        modifier = Modifier
            .height(16.dp),
    )
    CompositionLocalProvider(
        LocalTextStyle provides secondaryTextStyle,
    ) {
        Column {
            val labelAppPassword = stringResource(Res.string.app_password)
            val labelHash = stringResource(Res.string.encryption_hash)
            val labelSalt = stringResource(Res.string.encryption_salt)
            val labelKey = stringResource(Res.string.encryption_key)
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.textHorizontalPadding),
                text = stringResource(
                    Res.string.datasafety_local_encryption_algorithm_intro,
                ),
            )
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            TwoColumnRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.textHorizontalPadding),
                title = labelSalt,
                value = stringResource(Res.string.encryption_random_bits_data, 64),
            )
            HorizontalDivider(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .padding(horizontal = Dimens.textHorizontalPadding),
            )
            TwoColumnRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.textHorizontalPadding),
                title = labelHash,
                value = "PBKDF2($labelAppPassword, $labelSalt)",
            )
            HorizontalDivider(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .padding(horizontal = Dimens.textHorizontalPadding),
            )
            TwoColumnRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.textHorizontalPadding),
                title = labelKey,
                value = "PBKDF2($labelAppPassword, $labelHash)",
            )
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.textHorizontalPadding),
                text = stringResource(
                    Res.string.datasafety_local_encryption_algorithm_outro,
                ),
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier
            .padding(vertical = 16.dp),
    )
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(
            Res.string.datasafety_local_unlocking_vault,
            stringResource(Res.string.encryption_key),
        ),
    )
    LargeSection(
        text = stringResource(Res.string.datasafety_remote_section),
    )
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(Res.string.datasafety_remote_text),
    )
    TextButton(
        modifier = Modifier
            .padding(
                vertical = 4.dp,
                horizontal = Dimens.buttonHorizontalPadding,
            ),
        onClick = {
            val intent = NavigationIntent.NavigateToBrowser(
                url = "https://bitwarden.com/help/what-encryption-is-used/",
            )
            navigationController.queue(intent)
        },
    ) {
        Text(
            text = stringResource(Res.string.learn_more),
        )
    }
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
