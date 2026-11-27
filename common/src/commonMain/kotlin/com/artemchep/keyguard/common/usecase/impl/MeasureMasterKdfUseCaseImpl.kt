package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import kotlin.time.Duration
import kotlin.time.TimeSource

class MeasureMasterKdfUseCaseImpl(
    private val authGenerateMasterKeyUseCase: AuthGenerateMasterKeyUseCase,
) : MeasureMasterKdfUseCase {
    companion object {
        // The time of a derivation does not depend on the password, so
        // a throwaway one stands in for it. The salt is random.
        private const val THROWAWAY_PASSWORD = "keyguard-measure-master-kdf"
    }

    override fun invoke(
        kdf: MasterKdf.Argon2id,
    ): IO<Duration> = ioEffect {
        val password = MasterPassword.of(THROWAWAY_PASSWORD)
        // Run both stages of the derivation, as an unlock does.
        val mark = TimeSource.Monotonic.markNow()
        val result = authGenerateMasterKeyUseCase(kdf)(password).bind()
        val elapsed = mark.elapsedNow()
        result.key.byteArray.fill(0)
        elapsed
    }
}
