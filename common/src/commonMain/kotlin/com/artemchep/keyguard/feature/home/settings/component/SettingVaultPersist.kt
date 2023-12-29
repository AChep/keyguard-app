package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetVaultPersist
import com.artemchep.keyguard.common.usecase.PutVaultPersist
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import dev.icerock.moko.resources.compose.stringResource
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
    FlatItemLayout(
        leading = icon<RowScope>(Icons.Outlined.Key, secondaryIcon),
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(stringResource(Res.strings.pref_item_persist_vault_key_title))
                },
                text = {
                    val text = if (checked) {
                        stringResource(Res.strings.pref_item_persist_vault_key_text_on)
                    } else {
                        stringResource(Res.strings.pref_item_persist_vault_key_text_off)
                    }
                    Text(
                        modifier = Modifier
                            .animateContentSize(),
                        text = text,
                    )
                },
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )

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
            text = stringResource(Res.strings.pref_item_persist_vault_key_note),
        )
    }
}
