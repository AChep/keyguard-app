package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import org.kodein.di.DirectDI

internal actual fun createPutKeePassAccountNameById(
    directDI: DirectDI,
): PutKeePassAccountNameById = UnsupportedPutKeePassAccountNameById

private object UnsupportedPutKeePassAccountNameById : PutKeePassAccountNameById {
    override fun invoke(
        accountName: String,
        token: KeePassToken,
        profile: BitwardenProfile,
    ): IO<Unit> = ioEffect {
        throw UnsupportedOperationException("KeePass account name updates are not supported on this platform.")
    }
}
