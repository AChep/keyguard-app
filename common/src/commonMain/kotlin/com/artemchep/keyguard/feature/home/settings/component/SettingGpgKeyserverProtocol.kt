package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.PutGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.home.settings.KgPicker
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

fun settingGpgKeyserverProtocolProvider(
    directDI: DirectDI,
) = settingGpgKeyserverProtocolProvider(
    getGpgKeyserverConfig = directDI.instance(),
    putGpgKeyserverConfig = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingGpgKeyserverProtocolProvider(
    getGpgKeyserverConfig: GetGpgKeyserverConfig,
    putGpgKeyserverConfig: PutGpgKeyserverConfig,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getGpgKeyserverConfig()
    .map { config ->
        val dropdown = GpgKeyserverConfig.Protocol.entries
            .map { protocol ->
                FlatItemAction(
                    title = TextHolder.Value(protocol.name),
                    selected = protocol == config.protocol,
                    onClick = {
                        putGpgKeyserverConfig(config.copy(protocol = protocol))
                            .launchIn(windowCoroutineScope)
                    },
                )
            }

        SettingIi(
            platformClasses = listOf(
                Platform.Mobile.Android::class,
                Platform.Desktop.Linux::class,
                Platform.Desktop.MacOS::class,
                Platform.Desktop.Windows::class,
                Platform.Desktop.Other::class,
            ),
            search = SettingIi.Search(
                group = "security",
                tokens = listOf(
                    "gpg",
                    "keyserver",
                    "protocol",
                    "openpgp",
                    "hkp",
                    "vks",
                ),
            ),
        ) {
            LocalSettingPaneComponents.current.KgPicker(
                icon = Icons.Outlined.Dns,
                title = stringResource(Res.string.pref_item_gpg_keyserver_protocol_title),
                text = config.protocol.name,
                dropdown = dropdown,
            )
        }
    }
