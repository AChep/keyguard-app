package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasWatch
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.flowOf
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI

fun settingSshAgentLocalStorageInfoProvider(
    directDI: DirectDI,
) = settingSshAgentLocalStorageInfoProvider()

fun settingSshAgentLocalStorageInfoProvider(): SettingComponent = kotlin.run {
    if (CurrentPlatform.hasWatch()) {
        return@run flowOf(null)
    }

    val item = SettingIi(
        platformClasses = listOf(
            Platform.Mobile.Android::class,
            Platform.Desktop.Linux::class,
            Platform.Desktop.MacOS::class,
        ),
        search = SettingIi.Search(
            group = "security",
            tokens = listOf(
                "ssh",
                "git",
                "agent",
                "cache",
                "public",
                "storage",
                "private",
                "key",
            ),
        ),
    ) {
        Column(
            modifier = Modifier,
        ) {
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.textHorizontalPadding),
                text = stringResource(Res.string.pref_item_ssh_agent_local_storage_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            Text(
                modifier = Modifier
                    .padding(horizontal = Dimens.textHorizontalPadding),
                text = stringResource(Res.string.pref_item_ssh_agent_local_storage_text),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
        }
    }
    flowOf(item)
}
