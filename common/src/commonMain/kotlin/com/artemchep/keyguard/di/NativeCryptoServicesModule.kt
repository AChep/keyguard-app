package com.artemchep.keyguard.di

import com.artemchep.keyguard.common.service.crypto.FileEncryptionCodec
import com.artemchep.keyguard.common.service.crypto.GpgOpenPgpService
import com.artemchep.keyguard.crypto.NativeFileEncryptionCodec
import com.artemchep.keyguard.crypto.NativeGpgOpenPgpService
import org.koin.dsl.module

/** Native platform services whose staging implementation remains inside common. */
class NativeCryptoServicesModule {
    val module = module {
        single<FileEncryptionCodec> {
            NativeFileEncryptionCodec(
                cryptoGenerator = get(),
                stagingSpoolFactory = get(),
            )
        }
        single<GpgOpenPgpService> {
            NativeGpgOpenPgpService(stagingSpoolFactory = get())
        }
    }
}
