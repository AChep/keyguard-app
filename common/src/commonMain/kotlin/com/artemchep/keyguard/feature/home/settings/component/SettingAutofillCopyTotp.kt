package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.PutAutofillCopyTotp
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.map
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingAutofillCopyTotpProvider(
    directDI: DirectDI,
) = settingAutofillCopyTotpProvider(
    getAutofillCopyTotp = directDI.instance(),
    putAutofillCopyTotp = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingAutofillCopyTotpProvider(
    getAutofillCopyTotp: GetAutofillCopyTotp,
    putAutofillCopyTotp: PutAutofillCopyTotp,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getAutofillCopyTotp().map { copyTotp ->
    val onCheckedChange = { shouldCopyTotp: Boolean ->
        putAutofillCopyTotp(shouldCopyTotp)
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "autofill",
            tokens = listOf(
                "autofill",
                "copy",
                "totp",
                "password",
                "one-time",
            ),
        ),
    ) {
        SettingAutofillCopyTotp(
            checked = copyTotp,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingAutofillCopyTotp(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
) {
    FlatItem(
        trailing = {
            Switch(
                checked = checked,
                enabled = onCheckedChange != null,
                onCheckedChange = onCheckedChange,
            )
        },
        title = {
            Text(
                text = stringResource(Res.strings.pref_item_autofill_auto_copy_otp_title),
            )
        },
        text = {
            Text(
                text = stringResource(Res.strings.pref_item_autofill_auto_copy_otp_text),
            )
        },
        onClick = onCheckedChange?.partially1(!checked),
    )
}
