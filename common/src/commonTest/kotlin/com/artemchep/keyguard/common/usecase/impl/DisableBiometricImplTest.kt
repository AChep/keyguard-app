package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DisableBiometricImplTest {
    @Test
    fun `clears biometric token before deleting the platform credential`() {
        val events = mutableListOf<String>()
        val repository = FakeFingerprintRepository(
            initialValue = fingerprint(),
            events = events,
        )
        val useCase = DisableBiometricImpl(
            keyReadWriteRepository = repository,
            biometricKeyRepository = biometricKeyRepository(events),
        )

        useCase().bindBlocking()

        assertNull(repository.value?.biometric)
        assertEquals(listOf("put", "delete"), events)
    }

    @Test
    fun `cleanup failure does not restore biometric token`() {
        val events = mutableListOf<String>()
        val repository = FakeFingerprintRepository(
            initialValue = fingerprint(),
            events = events,
        )
        val useCase = DisableBiometricImpl(
            keyReadWriteRepository = repository,
            biometricKeyRepository = biometricKeyRepository(
                events = events,
                deleteFailure = IllegalStateException("cleanup failed"),
            ),
        )

        useCase().bindBlocking()

        assertNull(repository.value?.biometric)
        assertEquals(listOf("put", "delete"), events)
    }

    @Test
    fun `persistence failure preserves platform credential`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("persistence failed")
        val repository = FakeFingerprintRepository(
            initialValue = fingerprint(),
            events = events,
            putFailure = failure,
        )
        val useCase = DisableBiometricImpl(
            keyReadWriteRepository = repository,
            biometricKeyRepository = biometricKeyRepository(events),
        )

        val actualFailure = assertFailsWith<IllegalStateException> {
            useCase().bindBlocking()
        }

        assertSame(failure, actualFailure)
        assertNotNull(repository.value?.biometric)
        assertEquals(listOf("put"), events)
    }

    @Test
    fun `persistence cancellation propagates without starting cleanup`() {
        val events = mutableListOf<String>()
        val failure = CancellationException("persistence canceled")
        val repository = FakeFingerprintRepository(
            initialValue = fingerprint(),
            events = events,
            putFailure = failure,
        )
        val useCase = DisableBiometricImpl(
            keyReadWriteRepository = repository,
            biometricKeyRepository = biometricKeyRepository(events),
        )

        val actualFailure = assertFailsWith<CancellationException> {
            useCase().bindBlocking()
        }

        assertSame(failure, actualFailure)
        assertNotNull(repository.value?.biometric)
        assertEquals(listOf("put"), events)
    }

    @Test
    fun `already disabled biometrics skip persistence but delete orphaned credential`() {
        val events = mutableListOf<String>()
        val repository = FakeFingerprintRepository(
            initialValue = fingerprint().copy(biometric = null),
            events = events,
        )
        val useCase = DisableBiometricImpl(
            keyReadWriteRepository = repository,
            biometricKeyRepository = biometricKeyRepository(events),
        )

        useCase().bindBlocking()

        assertNull(repository.value?.biometric)
        assertEquals(listOf("delete"), events)
    }

    @Test
    fun `cleanup cancellation propagates after biometric token is cleared`() {
        val events = mutableListOf<String>()
        val failure = CancellationException("cleanup canceled")
        val repository = FakeFingerprintRepository(
            initialValue = fingerprint(),
            events = events,
        )
        val useCase = DisableBiometricImpl(
            keyReadWriteRepository = repository,
            biometricKeyRepository = biometricKeyRepository(
                events = events,
                deleteFailure = failure,
            ),
        )

        val actualFailure = assertFailsWith<CancellationException> {
            useCase().bindBlocking()
        }

        assertSame(failure, actualFailure)
        assertNull(repository.value?.biometric)
        assertEquals(listOf("put", "delete"), events)
    }
}

private class FakeFingerprintRepository(
    initialValue: Fingerprint?,
    private val events: MutableList<String>,
    private val putFailure: Throwable? = null,
) : FingerprintReadWriteRepository {
    var value: Fingerprint? = initialValue
        private set

    override fun get(): Flow<Fingerprint?> = flowOf(value)

    override fun put(key: Fingerprint?): IO<Unit> = ioEffect {
        events += "put"
        putFailure?.let { throw it }
        value = key
    }
}

private fun biometricKeyRepository(
    events: MutableList<String>,
    deleteFailure: Throwable? = null,
): BiometricKeyRepository = object : BiometricKeyRepository {
    override fun delete(): IO<Unit> = ioEffect {
        events += "delete"
        deleteFailure?.let { throw it }
    }
}

private fun fingerprint() = Fingerprint(
    version = MasterKdfVersion.LATEST,
    master = FingerprintPassword(
        hash = MasterPasswordHash(
            version = MasterKdfVersion.LATEST,
            byteArray = byteArrayOf(1),
        ),
        salt = MasterPasswordSalt(byteArrayOf(2)),
    ),
    biometric = FingerprintBiometric(
        iv = byteArrayOf(3),
        encryptedMasterKey = byteArrayOf(4),
    ),
)
