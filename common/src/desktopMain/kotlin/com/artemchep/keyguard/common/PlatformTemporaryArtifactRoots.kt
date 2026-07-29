package com.artemchep.keyguard.common

import com.artemchep.keyguard.copy.DataDirectory
import com.artemchep.keyguard.platform.LocalPath

internal actual fun platformTemporaryArtifactRoots(): List<TemporaryArtifactRoot> {
    val dataDirectory = DataDirectory()
    return listOf(
        TemporaryArtifactRoot(
            label = "desktop-data",
            provideDirectory = {
                LocalPath(dataDirectory.dataBlocking())
            },
        ),
        TemporaryArtifactRoot(
            label = "desktop-downloads",
            provideDirectory = {
                LocalPath(dataDirectory.downloadsBlocking())
            },
        ),
    )
}
