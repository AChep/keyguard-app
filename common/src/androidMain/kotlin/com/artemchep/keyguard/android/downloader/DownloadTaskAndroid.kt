package com.artemchep.keyguard.android.downloader

import android.app.Application
import android.content.Context
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.FileEncryptor
import com.artemchep.keyguard.copy.download.DownloadTaskJvm
import okhttp3.OkHttpClient
import org.kodein.di.DirectDI
import org.kodein.di.instance

class DownloadTaskAndroid(
    private val context: Context,
    cryptoGenerator: CryptoGenerator,
    okHttpClient: OkHttpClient,
    fileEncryptor: FileEncryptor,
) : DownloadTaskJvm(
    cacheDirProvider = {
        context.cacheDir
    },
    cryptoGenerator = cryptoGenerator,
    okHttpClient = okHttpClient,
    fileEncryptor = fileEncryptor,
) {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
        cryptoGenerator = directDI.instance(),
        okHttpClient = directDI.instance(),
        fileEncryptor = directDI.instance(),
    )
}
