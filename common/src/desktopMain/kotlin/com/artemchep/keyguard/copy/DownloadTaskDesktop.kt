package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.common.service.download.CacheDirProvider
import com.artemchep.keyguard.copy.download.DownloadTaskJvm
import okhttp3.OkHttpClient
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DownloadTaskDesktop(
    cacheDirProvider: CacheDirProvider,
    cryptoGenerator: CryptoGenerator,
    okHttpClient: OkHttpClient,
    fileEncryptor: FileEncryptor,
) : DownloadTaskJvm(
    cacheDirProvider = cacheDirProvider,
    cryptoGenerator = cryptoGenerator,
    okHttpClient = okHttpClient,
    fileEncryptor = fileEncryptor,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        cacheDirProvider = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        okHttpClient = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )
}
