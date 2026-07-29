package com.artemchep.keyguard.common

import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.common.service.download.store.DownloadFileStoreAndroid
import com.artemchep.keyguard.util.io.toLocalPath

internal actual fun platformTemporaryArtifactRoots(): List<TemporaryArtifactRoot> = listOf(
    TemporaryArtifactRoot(
        label = "android-files",
        provideDirectory = {
            BaseApp.context
                ?.filesDir
                ?.toLocalPath()
        },
    ),
    TemporaryArtifactRoot(
        label = "android-downloads",
        provideDirectory = {
            BaseApp.context
                ?.let(DownloadFileStoreAndroid::getDir)
                ?.toLocalPath()
        },
    ),
)
