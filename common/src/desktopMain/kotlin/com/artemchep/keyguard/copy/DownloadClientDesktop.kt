package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.copy.download.DownloadClientJvm
import okhttp3.OkHttpClient
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File

class DownloadClientDesktop(
    private val dataDirectory: DataDirectory,
    cryptoGenerator: CryptoGenerator,
    windowCoroutineScope: WindowCoroutineScope,
    okHttpClient: OkHttpClient,
    fileEncryptor: FileEncryptor,
) : DownloadClientJvm(
    cacheDirProvider = {
        val path = dataDirectory.cache()
            .bind()
        File(path)
    },
    cryptoGenerator = cryptoGenerator,
    windowCoroutineScope = windowCoroutineScope,
    okHttpClient = okHttpClient,
    fileEncryptor = fileEncryptor,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        dataDirectory = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
        okHttpClient = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )
}
