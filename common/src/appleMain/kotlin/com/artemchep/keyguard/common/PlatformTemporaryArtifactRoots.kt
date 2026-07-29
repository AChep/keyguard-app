package com.artemchep.keyguard.common

import com.artemchep.keyguard.platform.appleKeyguardDataDirectory
import com.artemchep.keyguard.util.io.resolve

internal actual fun platformTemporaryArtifactRoots(): List<TemporaryArtifactRoot> = listOf(
    TemporaryArtifactRoot(
        label = "apple-data",
        provideDirectory = {
            appleKeyguardDataDirectory()
        },
    ),
    TemporaryArtifactRoot(
        label = "apple-downloads",
        provideDirectory = {
            appleKeyguardDataDirectory().resolve("downloads")
        },
    ),
)
