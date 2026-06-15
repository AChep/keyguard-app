package com.artemchep.keyguard.provider.bitwarden.usecase

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import org.kodein.di.DirectDI

internal actual fun createPutKeePassAccountColorById(
    directDI: DirectDI,
): PutKeePassAccountColorById = UnsupportedPutKeePassAccountColorById

private object UnsupportedPutKeePassAccountColorById : PutKeePassAccountColorById {
    override fun invoke(
        color: Color,
        token: KeePassToken,
        profile: BitwardenProfile,
    ): IO<Unit> = ioEffect {
        throw UnsupportedOperationException("KeePass account color updates are not supported on this platform.")
    }
}
