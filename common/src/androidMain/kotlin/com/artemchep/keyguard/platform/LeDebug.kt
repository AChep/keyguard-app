package com.artemchep.keyguard.platform

 import com.artemchep.keyguard.common.BuildConfig

actual val isStandalone: Boolean = BuildConfig.FLAVOR == "none"
