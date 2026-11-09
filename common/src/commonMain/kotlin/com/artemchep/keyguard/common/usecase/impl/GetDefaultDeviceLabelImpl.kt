package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.usecase.GetDefaultDeviceLabel
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.firstDeviceLabel
import com.artemchep.keyguard.platform.platformDeviceLabel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_device_name_default_android
import com.artemchep.keyguard.res.app_link_device_name_default_desktop
import com.artemchep.keyguard.res.app_link_device_name_default_ios
import com.artemchep.keyguard.res.app_link_device_name_default_macos
import com.artemchep.keyguard.res.app_link_device_name_default_wear
import org.jetbrains.compose.resources.StringResource

class GetDefaultDeviceLabelImpl(
    private val context: LeContext,
) : GetDefaultDeviceLabel {
    override fun invoke(): IO<String> = ioEffect {
        firstDeviceLabel({ platformDeviceLabel(context) })
            ?: textResource(CurrentPlatform.defaultDeviceLabelRes(), context)
    }
}

internal fun Platform.defaultDeviceLabelRes(): StringResource = when (this) {
    is Platform.Mobile.Android -> if (isWatch) {
        Res.string.app_link_device_name_default_wear
    } else {
        Res.string.app_link_device_name_default_android
    }

    is Platform.Mobile.Ios -> Res.string.app_link_device_name_default_ios
    is Platform.Desktop.MacOS -> Res.string.app_link_device_name_default_macos
    is Platform.Desktop.Linux,
    is Platform.Desktop.Windows,
    is Platform.Desktop.Other,
    -> Res.string.app_link_device_name_default_desktop
}
