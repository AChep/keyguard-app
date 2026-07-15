package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.android.BaseApp

internal actual fun createPrivateTemporaryStorage(): PrivateTemporaryStorage {
    val context = checkNotNull(BaseApp.context) {
        "Android application context is unavailable for private temporary storage"
    }
    return createPrivateTemporaryStorageJvm(
        directory = context.cacheDir,
    )
}
