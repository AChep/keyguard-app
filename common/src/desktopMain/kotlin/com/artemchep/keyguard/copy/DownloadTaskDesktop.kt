package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.copy.download.DownloadClientJvm
import com.artemchep.keyguard.copy.download.DownloadTaskJvm
import okhttp3.OkHttpClient
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.File

class DownloadTaskDesktop(
    private val dataDirectory: DataDirectory,
    cryptoGenerator: CryptoGenerator,
    okHttpClient: OkHttpClient,
    fileEncryptor: FileEncryptor,
) : DownloadTaskJvm(
    cacheDirProvider = {
        val path = dataDirectory.cache()
            .bind()
        File(path)
    },
    cryptoGenerator = cryptoGenerator,
    okHttpClient = okHttpClient,
    fileEncryptor = fileEncryptor,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        dataDirectory = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        okHttpClient = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )
}
