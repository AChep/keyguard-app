package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.usecase.AuthGenerateMasterKeyUseCase
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class MeasureMasterKdfUseCaseImplTest {
    private val kdf = MasterKdf.Argon2id(
        iterations = 2,
        memoryKib = 16384,
        parallelism = 1,
    )

    @Test
    fun `derives once with the given parameters and wipes the key`() {
        val usedKdfs = mutableListOf<MasterKdf>()
        val keyBytes = byteArrayOf(1, 2, 3)
        val useCase = MeasureMasterKdfUseCaseImpl(
            authGenerateMasterKeyUseCase = object : AuthGenerateMasterKeyUseCase {
                override fun invoke(kdf: MasterKdf.Argon2id): AuthMasterKeyUseCase = {
                    usedKdfs += kdf
                    io(result(kdf, keyBytes))
                }
            },
        )

        val duration = useCase(kdf).bindBlocking()

        assertEquals(listOf<MasterKdf>(kdf), usedKdfs)
        assertTrue(duration >= Duration.ZERO)
        assertTrue(keyBytes.all { it == 0.toByte() })
    }

    @Test
    fun `a measurement waits for the derivation of another instance, even a cancelled one`() = runTest {
        val started = Channel<Unit>(Channel.UNLIMITED)
        val derived = CompletableDeferred<Unit>()

        // Like the native call, the derivation can not be cancelled.
        fun newUseCase() = MeasureMasterKdfUseCaseImpl(
            authGenerateMasterKeyUseCase = object : AuthGenerateMasterKeyUseCase {
                override fun invoke(kdf: MasterKdf.Argon2id): AuthMasterKeyUseCase = {
                    ioEffect {
                        started.send(Unit)
                        withContext(NonCancellable) { derived.await() }
                        result(kdf, byteArrayOf(1))
                    }
                }
            },
        )

        // The first editor starts a test, then closes.
        val first = launch { newUseCase()(kdf).bind() }
        runCurrent()
        assertTrue(started.tryReceive().isSuccess)
        first.cancel()
        runCurrent()

        // A reopened editor starts another one.
        val second = async { newUseCase()(kdf).bind() }
        runCurrent()
        assertTrue(started.tryReceive().isFailure)

        // Starts once the first native call returns.
        derived.complete(Unit)
        runCurrent()
        assertTrue(started.tryReceive().isSuccess)
        second.await()
        assertTrue(first.isCancelled)
    }

    private fun result(
        kdf: MasterKdf.Argon2id,
        keyBytes: ByteArray,
    ) = AuthResult(
        key = MasterKey(MasterKdfVersion.V2, keyBytes),
        token = FingerprintPassword.Wrapped(
            kdf = kdf,
            salt = MasterPasswordSalt(byteArrayOf(5)),
            wrappedKey = byteArrayOf(4),
        ),
    )
}
