package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.BiometricStatus
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.service.biometrics.BiometricKeyRepository
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.platform.LeBiometricCipher
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RearmBiometricBindingTest {
    @Test
    fun `restart loses key then password rearm restores the saved binding once`() = runTest {
        val fixture = Fixture()
        val old = fixture.tokens.biometric
        assertFalse(fixture.keyExists)
        assertTrue(fixture.rearm())
        assertTrue(fixture.keyExists)
        assertNotEquals(old, fixture.tokens.biometric)
        assertFalse(fixture.rearm())
        assertEquals(1, fixture.creations)
    }

    @Test
    fun `failed persistence removes the orphan key and permits retry`() = runTest {
        val fixture = Fixture()
        val old = fixture.tokens
        fixture.failWrite = true
        assertFailsWith<IllegalStateException> { fixture.rearm() }
        assertEquals(old, fixture.tokens)
        assertFalse(fixture.keyExists)
        fixture.failWrite = false
        assertTrue(fixture.rearm())
        assertTrue(fixture.keyExists)
        assertEquals(2, fixture.creations)
    }

    @Test
    fun `disabled and unavailable biometrics are not rearmed`() = runTest {
        val fixture = Fixture()
        assertFalse(fixture.rearm(BiometricStatus.Unavailable))
        fixture.tokens = fixture.tokens.copy(biometric = null)
        assertFalse(fixture.rearm())
        assertEquals(0, fixture.creations)
        assertFalse(fixture.keyExists)
    }

    private class Fixture {
        var tokens = Fingerprint(
            version = MasterKdfVersion.LATEST,
            master = FingerprintPassword(
                hash = MasterPasswordHash(MasterKdfVersion.LATEST, byteArrayOf(1)),
                salt = MasterPasswordSalt(byteArrayOf(2)),
            ),
            biometric = FingerprintBiometric(byteArrayOf(3), byteArrayOf(4)),
        )
        var keyExists = false
        var creations = 0
        var failWrite = false
        private val keyRepository = object : BiometricKeyRepository {
            override fun exists() = io(keyExists)
            override fun delete() = ioEffect { keyExists = false }
        }
        private val repository = object : FingerprintReadWriteRepository {
            override fun get() = flowOf(tokens)
            override fun put(key: Fingerprint?): IO<Unit> = ioEffect {
                check(!failWrite) { "Disk unavailable" }
                tokens = requireNotNull(key)
            }
        }
        private val status = BiometricStatus.Available(
            createCipher = {
                object : LeBiometricCipher {
                    override val iv = byteArrayOf(5)
                    override fun encode(data: ByteArray): ByteArray {
                        keyExists = true
                        creations++
                        return byteArrayOf(6)
                    }
                }
            },
        )

        suspend fun rearm(biometric: BiometricStatus = status) = rearmBiometricBinding(
            tokens = tokens,
            masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(7)),
            biometric = biometric,
            biometricKeyRepository = keyRepository,
            keyReadWriteRepository = repository,
            biometricKeyEncryptUseCase = BiometricKeyEncryptUseCaseImpl(),
        )
    }
}
