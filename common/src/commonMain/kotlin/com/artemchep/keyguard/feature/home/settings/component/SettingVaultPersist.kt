package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.PutVaultPersist
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgSwitch
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingVaultPersistProvider(
    directDI: DirectDI,
) = settingVaultPersistProvider(
    getVaultPersist = directDI.instance(),
    putVaultPersist = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingVaultPersistProvider(
    getVaultPersist: GetVaultPersist,
    putVaultPersist: PutVaultPersist,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getVaultPersist().map { persist ->
    val onCheckedChange = { shouldPersist: Boolean ->
        putVaultPersist(shouldPersist)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "lock",
            tokens = listOf(
                "vault",
                "lock",
                "persist",
                "disk",
            ),
        ),
    ) {
        SettingVaultPersist(
            checked = persist,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingVaultPersist(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    val secondaryIcon = if (checked) Icons.Outlined.Memory else Icons.Outlined.Storage
    val text = if (checked) {
        stringResource(Res.string.pref_item_persist_vault_key_text_on)
    } else {
        stringResource(Res.string.pref_item_persist_vault_key_text_off)
    }
    LocalSettingPaneComponents.current.KgSwitch(
        icon = Icons.Outlined.Key,
        subIcon = secondaryIcon,
        title = stringResource(Res.string.pref_item_persist_vault_key_title),
        text = text,
        footer = {
            ExpandedIfNotEmpty(
                valueOrNull = Unit.takeIf { checked },
            ) {
                FlatSimpleNote(
                    modifier = Modifier
                        .padding(
                            top = 8.dp,
                            bottom = 8.dp,
                            start = Dimens.horizontalPadding * 1 + 24.dp,
                        ),
                    type = SimpleNote.Type.WARNING,
                    text = stringResource(Res.string.pref_item_persist_vault_key_note),
                )
            }
        },
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}
