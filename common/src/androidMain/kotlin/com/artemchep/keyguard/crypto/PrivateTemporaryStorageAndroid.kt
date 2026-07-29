package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.util.io.toLocalPath

internal actual fun privateTemporaryStorageDirectory(): LocalPath {
    val context = checkNotNull(BaseApp.context) {
        "Android application context is unavailable for private temporary storage"
    }
    return context.cacheDir.toLocalPath()
}
