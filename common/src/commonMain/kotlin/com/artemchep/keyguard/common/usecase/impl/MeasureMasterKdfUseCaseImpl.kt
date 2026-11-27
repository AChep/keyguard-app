package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.TimeSource

class MeasureMasterKdfUseCaseImpl(
    private val authGenerateMasterKeyUseCase: AuthGenerateMasterKeyUseCase,
) : MeasureMasterKdfUseCase {
    companion object {
        // The time of a derivation does not depend on the password, so
        // a throwaway one stands in for it. The salt is random.
        private const val THROWAWAY_PASSWORD = "keyguard-measure-master-kdf"

        /**
         * Runs one measurement at a time, app-wide. The native derivation can not
         * be cancelled: a cancelled measurement keeps the lock until its native
         * call returns, so a reopened editor can not start a second one alongside.
         */
        private val mutex = Mutex()
    }

    override fun invoke(
        kdf: MasterKdf.Argon2id,
    ): IO<Duration> = ioEffect {
        mutex.withLock {
            val password = MasterPassword.of(THROWAWAY_PASSWORD)
            // Run the derivation and the wrap of a new
            // master key, as an unlock does.
            val mark = TimeSource.Monotonic.markNow()
            val result = authGenerateMasterKeyUseCase(kdf)(password).bind()
            val elapsed = mark.elapsedNow()
            result.key.byteArray.fill(0)
            elapsed
        }
    }
}
