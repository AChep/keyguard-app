package com.artemchep.autotype

import com.artemchep.jna.DesktopLibJna
import com.artemchep.jna.withDesktopLib
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public suspend fun biometricsIsSupported(): Boolean = withDesktopLib { lib ->
    lib.biometricsIsSupported()
}

public suspend fun biometricsVerify(
    title: String,
) {
    withContext(Dispatchers.IO) {
        biometricsVerifyOrThrow(
            lib = DesktopLibJna.get(),
            title = title,
        )
    }
}
