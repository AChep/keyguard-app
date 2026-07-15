package com.artemchep.keyguard.crypto

internal actual fun createPrivateTemporaryStorage(
): PrivateTemporaryStorage = createPrivateTemporaryStorageJvm(
    directory = null,
)
