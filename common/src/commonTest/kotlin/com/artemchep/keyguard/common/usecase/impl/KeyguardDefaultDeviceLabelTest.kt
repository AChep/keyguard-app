package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.provider.keyguard.keyguardPlatformName
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_device_name_default_android
import com.artemchep.keyguard.res.app_link_device_name_default_desktop
import com.artemchep.keyguard.res.app_link_device_name_default_ios
import com.artemchep.keyguard.res.app_link_device_name_default_macos
import com.artemchep.keyguard.res.app_link_device_name_default_wear
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyguardDefaultDeviceLabelTest {
    private val phone = Platform.Mobile.Android(isChromebook = false, isWatch = false, sdk = 34)

    private val watch = Platform.Mobile.Android(isChromebook = false, isWatch = true, sdk = 34)

    @Test
    fun `each platform suggests its own device label`() {
        assertEquals(Res.string.app_link_device_name_default_android, phone.defaultDeviceLabelRes())
        assertEquals(Res.string.app_link_device_name_default_wear, watch.defaultDeviceLabelRes())
        assertEquals(Res.string.app_link_device_name_default_ios, Platform.Mobile.Ios.Native.defaultDeviceLabelRes())
        assertEquals(
            Res.string.app_link_device_name_default_macos,
            Platform.Desktop.MacOS.Native.defaultDeviceLabelRes(),
        )
        assertEquals(
            Res.string.app_link_device_name_default_macos,
            Platform.Desktop.MacOS.Jvm.defaultDeviceLabelRes(),
        )
        assertEquals(Res.string.app_link_device_name_default_desktop, Platform.Desktop.Windows.defaultDeviceLabelRes())
        assertEquals(
            Res.string.app_link_device_name_default_desktop,
            Platform.Desktop.Linux.native.defaultDeviceLabelRes(),
        )
    }

    @Test
    fun `each platform names itself in its device record`() {
        assertEquals("android", phone.keyguardPlatformName())
        assertEquals("wear", watch.keyguardPlatformName())
        assertEquals("ios", Platform.Mobile.Ios.Native.keyguardPlatformName())
        assertEquals("macos", Platform.Desktop.MacOS.Native.keyguardPlatformName())
        assertEquals("windows", Platform.Desktop.Windows.keyguardPlatformName())
        assertEquals("linux", Platform.Desktop.Linux(isFlatpak = true).keyguardPlatformName())
        assertEquals("desktop", Platform.Desktop.Other.keyguardPlatformName())
    }
}
