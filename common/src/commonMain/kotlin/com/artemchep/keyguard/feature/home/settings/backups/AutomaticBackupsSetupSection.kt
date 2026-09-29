package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingItemShape
import com.artemchep.keyguard.feature.home.settings.SettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.automaticBackupsDisabledContent(
    onSetupClick: () -> Unit,
    isSupported: Boolean,
    setupSectionTitle: String,
    components: SettingPaneComponents,
) {
    if (!isSupported) {
        automaticBackupsSettingsHeader(
            key = "unsupported",
            title = setupSectionTitle,
        )
        item("unsupported.unsupported") {
            CompositionLocalProvider(
                LocalSettingItemShape provides ShapeState.ALL,
            ) {
                components.KgAction(
                    icon = Icons.Outlined.Info,
                    title = stringResource(Res.string.pref_item_automatic_backups_unsupported_title),
                    text = stringResource(Res.string.pref_item_automatic_backups_unsupported_text),
                    enabled = false,
                )
            }
        }
        return
    }
    item("setup.intro") {
        AutomaticBackupsSetupIntro(onSetup = onSetupClick)
    }
    item("details") {
        AutomaticBackupsDetailsSection()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AutomaticBackupsSetupIntro(
    onSetup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraLarge,
            )
            .padding(
                horizontal = Dimens.horizontalPadding,
                vertical = Dimens.verticalPadding,
            ),
    ) {
        Avatar {
            Icon(
                imageVector = Icons.Outlined.Backup,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(Dimens.verticalPadding))
        Text(
            text = stringResource(Res.string.pref_item_automatic_backups_wizard_ready_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(Res.string.pref_item_automatic_backups_wizard_ready_detail),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier
                .fillMaxWidth(),
            shapes = ButtonDefaults.shapes(),
            onClick = onSetup,
        ) {
            Text(stringResource(Res.string.pref_item_automatic_backups_wizard_title))
        }
    }
}

@Preview(name = "Setup introduction", group = "Backup settings", widthDp = 390)
@Composable
internal fun AutomaticBackupsSetupIntroPreview() {
    AutomaticBackupsPreview {
        AutomaticBackupsSetupIntro(onSetup = {})
    }
}
