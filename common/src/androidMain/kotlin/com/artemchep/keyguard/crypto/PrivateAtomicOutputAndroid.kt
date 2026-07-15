package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.platform.LocalPath

internal actual fun createPrivateTemporarySibling(
    destination: LocalPath,
): LocalPath = createPrivateTemporarySiblingJvm(destination)
