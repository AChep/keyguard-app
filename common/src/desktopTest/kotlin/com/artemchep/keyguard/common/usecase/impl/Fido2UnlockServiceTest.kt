package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.Fingerprint
import com.artemchep.keyguard.common.model.FingerprintBiometric
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.FingerprintReadWriteRepository
import com.artemchep.keyguard.common.service.vault.testVaultSession
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.crypto.CipherEncryptorImpl
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.util.fido2.Fido2Operation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

class Fido2UnlockServiceTest {
    @Test
    fun enrollmentRoundTripsAndWrongKeyCannotUnlock() = runTest {
        val fixture = Fixture()
        val secret = ByteArray(32) { 7 }
        fixture.enroll(secret)
        val tokens = assertNotNull(fixture.tokens.value)
        assertContentEquals(ByteArray(32), secret)
        assertEquals(fixture.original.master, tokens.master)
        assertContentEquals(
            fixture.key.byteArray,
            fixture.service.decrypt(tokens, ByteArray(32) { 7 }).byteArray,
        )
        assertFails { fixture.service.decrypt(tokens, ByteArray(32) { 8 }) }
        assertFails {
            fixture.service.decrypt(
                tokens.copy(fido2 = tokens.fido2!!.copy(encryptedMasterKey = "0.invalid")),
                ByteArray(32) { 7 },
            )
        }
        fixture.service.disable().bind()
        assertNull(fixture.tokens.value!!.fido2)
        assertEquals(fixture.original.master, fixture.tokens.value!!.master)
    }

    @Test
    fun failedOrCanceledEnrollmentPreservesExistingUnlockMethods() = runTest {
        val fixture = Fixture()
        fixture.enroll(ByteArray(32) { 7 })
        val enrolled = fixture.tokens.value
        assertFailsWith<CancellationException> {
            fixture.service
                .enroll { operation ->
                    if (operation is Fido2Operation.Register) byteArrayOf(1)
                    else throw CancellationException()
                }
                .bind()
        }
        assertSame(enrolled, fixture.tokens.value)
        val badSecret = ByteArray(31) { 7 }
        assertFailsWith<IllegalArgumentException> { fixture.enroll(badSecret) }
        assertContentEquals(ByteArray(31), badSecret)
        assertSame(enrolled, fixture.tokens.value)
    }

    @Test
    fun lockingOrChangingPasswordDuringEnrollmentPreventsSaving() = runTest {
        for (lock in listOf(true, false)) {
            val fixture = Fixture()
            val secret = ByteArray(32) { 7 }
            assertFailsWith<IllegalStateException> {
                fixture.service
                    .enroll { operation ->
                        if (operation is Fido2Operation.Register) byteArrayOf(1)
                        else {
                            if (lock) fixture.sessions.value = MasterSession.Empty()
                            else
                                fixture.tokens.value =
                                    fixture.original.copy(
                                        master =
                                            fixture.original.master.copy(
                                                salt = MasterPasswordSalt(byteArrayOf(9))
                                            )
                                    )
                            secret
                        }
                    }
                    .bind()
            }
            assertNull(fixture.tokens.value!!.fido2)
            assertContentEquals(ByteArray(32), secret)
        }
    }

    @Test
    fun enrollmentPreservesOtherFactorsChangedDuringHardwarePrompt() = runTest {
        val fixture = Fixture()
        val biometric = FingerprintBiometric(byteArrayOf(3), byteArrayOf(4))
        fixture.service
            .enroll { operation ->
                if (operation is Fido2Operation.Register) byteArrayOf(1)
                else {
                    fixture.tokens.value = fixture.original.copy(biometric = biometric)
                    ByteArray(32) { 7 }
                }
            }
            .bind()
        assertSame(biometric, fixture.tokens.value!!.biometric)
        assertNotNull(fixture.tokens.value!!.fido2)
    }

    private class Fixture {
        val key = MasterKey(MasterKdfVersion.LATEST, ByteArray(32) { 3 })
        val original =
            Fingerprint(
                MasterKdfVersion.LATEST,
                FingerprintPassword(
                    MasterPasswordHash(MasterKdfVersion.LATEST, byteArrayOf(1)),
                    MasterPasswordSalt(byteArrayOf(2)),
                ),
                biometric = null,
                yubiKey = null,
            )
        val tokens = MutableStateFlow<Fingerprint?>(original)
        val sessions =
            MutableStateFlow<MasterSession>(
                MasterSession.Key(
                    key,
                    testVaultSession(),
                    MasterSession.Key.Authenticated,
                    Clock.System.now(),
                )
            )
        private val crypto = CryptoGeneratorJvm()
        private val base64 = Base64ServiceJvm()
        val service =
            Fido2UnlockService(
                object : FingerprintReadWriteRepository {
                    override fun update(transform: (Fingerprint?) -> Fingerprint?) = ioEffect {
                        tokens.value = transform(tokens.value)
                    }

                    override fun get() = tokens

                    override fun put(key: Fingerprint?) = ioEffect { tokens.value = key }
                },
                object : GetVaultSession {
                    override val valueOrNull
                        get() = sessions.value

                    override fun invoke() = sessions
                },
                crypto,
                CipherEncryptorImpl(crypto, base64),
                base64,
            )

        suspend fun enroll(secret: ByteArray) =
            service
                .enroll { operation ->
                    when (operation) {
                        is Fido2Operation.Register -> byteArrayOf(1, 2, 3)
                        is Fido2Operation.Derive -> secret
                    }
                }
                .bind()
    }
}
