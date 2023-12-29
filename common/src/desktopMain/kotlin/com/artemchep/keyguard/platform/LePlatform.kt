package com.artemchep.keyguard.platform

import org.apache.commons.lang3.SystemUtils

actual val CurrentPlatform: Platform
    get() = when {
        SystemUtils.IS_OS_WINDOWS ->
            Platform.Desktop.Windows

        SystemUtils.IS_OS_MAC || SystemUtils.IS_OS_MAC_OSX ->
            Platform.Desktop.MacOS

        SystemUtils.IS_OS_LINUX ->
            Platform.Desktop.Linux

        else -> Platform.Desktop.Other
    }
