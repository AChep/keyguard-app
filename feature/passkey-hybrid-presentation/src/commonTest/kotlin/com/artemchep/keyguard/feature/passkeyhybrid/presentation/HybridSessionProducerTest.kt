package com.artemchep.keyguard.feature.passkeyhybrid.presentation

import com.artemchep.keyguard.util.fidohybrid.HybridAssertionRequest
import com.artemchep.keyguard.util.fidohybrid.HybridRegistrationRequest
import com.artemchep.keyguard.util.fidohybrid.HybridResult
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionResult
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticatorDataFactory
import com.artemchep.keyguard.util.webauthn.WebAuthnCredential
import com.artemchep.keyguard.util.webauthn.WebAuthnCredentialCreationResult
import com.artemchep.keyguard.util.webauthn.WebAuthnPublicKeyCredentialDescriptor
import com.artemchep.keyguard.util.webauthn.WebAuthnRegistrationRequest
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyCrypto
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyInspectionResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyMaterial
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyKeyProfile
import com.artemchep.keyguard.util.webauthn.crypto.PasskeyPublicKey
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignResult
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HybridSessionProducerTest {
    @Test
    fun signsOnlyAfterSelectionAndVerification() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            val pick = fixture.phase(HybridPhase.PICK)
            assertEquals(0, fixture.signatures)
            pick.onPick?.invoke(pick.candidates.single())
            val verification = fixture.phase(HybridPhase.VERIFY)
            assertEquals(0, fixture.signatures)
            verification.onVerified?.invoke()
            val success = fixture.phase(HybridPhase.SUCCESS)
            assertEquals(1, fixture.signatures)
            assertEquals("example.com", success.rpId)
            assertEquals(HybridOperation.SIGN_IN, success.operation)
        }

    @Test
    fun silentPreflightSignsWithoutInteractionOrPresence() =
        runTest {
            val fixture = Fixture(backgroundScope, silent = true)
            fixture.start()
            val success = fixture.phase(HybridPhase.SUCCESS)
            assertEquals(1, fixture.signatures)
            // Nothing was chosen or sent by the user, so the result names no request.
            assertNull(success.operation)
            // Neither user presence nor user verification is asserted.
            assertEquals(0, fixture.flags and 0x05)
        }

    @Test
    fun deletionDuringVerificationPreventsSigning() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            val pick = fixture.phase(HybridPhase.PICK)
            pick.onPick?.invoke(pick.candidates.single())
            val verification = fixture.phase(HybridPhase.VERIFY)
            fixture.vault.available = false
            verification.onVerified?.invoke()
            fixture.phase(HybridPhase.ERROR)
            assertEquals(0, fixture.signatures)
        }

    @Test
    fun lockingVaultInvalidatesPendingVerification() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.start()
            val pick = fixture.phase(HybridPhase.PICK)
            pick.onPick?.invoke(pick.candidates.single())
            val verification = fixture.phase(HybridPhase.VERIFY)
            fixture.vault.active.value = false
            fixture.phase(HybridPhase.ERROR)
            verification.onVerified?.invoke()
            assertEquals(0, fixture.signatures)
        }

    @Test
    fun unavailableCredentialsAndDeniedPermissionsNeverSign() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.vault.available = false
            fixture.start()
            assertEquals(HybridError.NO_CREDENTIALS, fixture.phase(HybridPhase.ERROR).error)
            assertEquals(0, fixture.signatures)
            val denied = Fixture(backgroundScope)
            denied.producer.state.value.onScan
                ?.invoke(QR)
            denied.producer.state.value.onPermission
                ?.invoke(false)
            assertEquals(HybridError.PERMISSION, denied.producer.state.value.error)
            assertEquals(0, denied.connections)
        }

    @Test
    fun registrationStoresSecretBeforeReportingSuccess() =
        runTest {
            val fixture = Fixture(backgroundScope, create = true)
            fixture.start()
            val save = fixture.phase(HybridPhase.SAVE)
            assertEquals(0, fixture.generations)
            save.onSaveTarget?.invoke(HybridSaveTarget("account", "item"))
            val verified = fixture.phase(HybridPhase.VERIFY)
            assertEquals(0, fixture.generations)
            verified.onVerified?.invoke()
            val success = fixture.phase(HybridPhase.SUCCESS)
            assertEquals(1, fixture.generations)
            assertEquals("example.com", success.rpId)
            assertEquals(HybridOperation.CREATE, success.operation)
            val stored = assertNotNull(fixture.vault.stored)
            assertNotNull(stored.prfSecret)
            assertEquals("example.com", stored.rpId)
        }

    @Test
    fun readOnlyRegistrationDoesNotGenerateOrStoreAKey() =
        runTest {
            val fixture = Fixture(backgroundScope, create = true)
            fixture.start()
            fixture
                .phase(HybridPhase.SAVE)
                .onSaveTarget
                ?.invoke(HybridSaveTarget("account", "item"))
            val verification = fixture.phase(HybridPhase.VERIFY)
            fixture.vault.writable = false
            verification.onVerified?.invoke()
            fixture.phase(HybridPhase.ERROR)
            assertEquals(0, fixture.generations)
            assertEquals(null, fixture.vault.stored)
        }

    @Test
    fun excludedCredentialPreventsGeneration() =
        runTest {
            val fixture = Fixture(backgroundScope, create = true)
            fixture.exclusions = setOf("00000000-0000-0000-0000-000000000001")
            fixture.start()
            fixture
                .phase(HybridPhase.SAVE)
                .onSaveTarget
                ?.invoke(HybridSaveTarget("account", "item"))
            fixture.phase(HybridPhase.VERIFY).onVerified?.invoke()
            assertEquals(HybridError.EXCLUDED, fixture.phase(HybridPhase.ERROR).error)
            assertEquals(0, fixture.generations)
        }

    @Test
    fun lockingDuringRegistrationPreventsGenerationAndStorage() =
        runTest {
            val fixture = Fixture(backgroundScope, create = true)
            fixture.start()
            fixture
                .phase(HybridPhase.SAVE)
                .onSaveTarget
                ?.invoke(HybridSaveTarget("account", "item"))
            val verification = fixture.phase(HybridPhase.VERIFY)
            fixture.vault.active.value = false
            fixture.phase(HybridPhase.ERROR)
            verification.onVerified?.invoke()
            assertEquals(0, fixture.generations)
            assertEquals(null, fixture.vault.stored)
        }

    @Test
    fun deletionDuringGenerationPreventsStorage() =
        runTest {
            val fixture = Fixture(backgroundScope, create = true)
            fixture.onGenerate = { fixture.vault.available = false }
            fixture.start()
            fixture.phase(HybridPhase.SAVE).onSaveTarget?.invoke(HybridSaveTarget("account", "item"))
            fixture.phase(HybridPhase.VERIFY).onVerified?.invoke()
            assertEquals(HybridError.WRITE, fixture.phase(HybridPhase.ERROR).error)
            assertEquals(1, fixture.generations)
            assertEquals(null, fixture.vault.stored)
        }

    @Test
    fun failedStorageCannotReportRegistrationSuccess() =
        runTest {
            val fixture = Fixture(backgroundScope, create = true)
            fixture.vault.storeFails = true
            fixture.start()
            fixture
                .phase(HybridPhase.SAVE)
                .onSaveTarget
                ?.invoke(HybridSaveTarget("account", "item"))
            fixture.phase(HybridPhase.VERIFY).onVerified?.invoke()
            fixture.phase(HybridPhase.ERROR)
            assertEquals(null, fixture.vault.stored)
            assertTrue(fixture.generations > 0)
        }

    private class Fixture(
        scope: CoroutineScope,
        private val create: Boolean = false,
        private val silent: Boolean = false,
    ) {
        var signatures = 0
        var flags = -1
        var generations = 0
        var onGenerate: () -> Unit = {}
        var exclusions: Set<String> = emptySet()
        var connections = 0
        val vault = Vault()
        private val authenticator =
            WebAuthnAuthenticator(
                json = Json,
                authenticatorDataFactory = WebAuthnAuthenticatorDataFactory(ByteArray(16), { ByteArray(32) }),
                decodeStoredPrivateKey = { byteArrayOf(1) },
                randomBytes = { ByteArray(it) { 9 } },
                passkeyCrypto =
                    object : PasskeyCrypto {
                        override val supportedAlgorithms = setOf(PasskeySignatureAlgorithm.ES256)

                        override fun generate(algorithm: PasskeySignatureAlgorithm): PasskeyKeyMaterial {
                            generations++
                            onGenerate()
                            return PasskeyKeyMaterial(
                                PasskeyKeyProfile.EC_P256,
                                byteArrayOf(1),
                                PasskeyPublicKey.EcP256(ByteArray(32), ByteArray(32), byteArrayOf(4)),
                            )
                        }

                        override fun inspect(privateKeyPkcs8: ByteArray): PasskeyKeyInspectionResult = error("unused")

                        override fun sign(
                            algorithm: PasskeySignatureAlgorithm,
                            privateKeyPkcs8: ByteArray,
                            data: ByteArray,
                        ): PasskeySignResult {
                            signatures++
                            return PasskeySignResult.Success(byteArrayOf(0x30, 0))
                        }
                    },
            )
        val producer =
            HybridSessionProducer(
                scope = scope,
                vault = vault,
                authenticator = authenticator,
                transport = { _, guard, waiting, handler ->
                    connections++
                    guard()
                    waiting()
                    val response =
                        if (create) {
                            handler.makeCredential(
                                HybridRegistrationRequest(
                                    WebAuthnRegistrationRequest(
                                        rpId = "example.com",
                                        userHandle = "dXNlcg",
                                        pubKeyCredParams =
                                            listOf(
                                                CreatePasskeyPubKeyCredParams(type = "public-key", alg = -7.0),
                                            ),
                                        discoverable = true,
                                        userName = "alice",
                                        userVerification = "required",
                                        excludedCredentialIds = exclusions,
                                    ),
                                ),
                            )
                        } else {
                            val allow =
                                if (silent) {
                                    val descriptor =
                                        WebAuthnPublicKeyCredentialDescriptor("public-key", CANDIDATE.credentialId)
                                    WebAuthnAllowedCredentialDescriptors(true, listOf(descriptor))
                                } else {
                                    WebAuthnAllowedCredentialDescriptors.NONE
                                }
                            handler.getAssertion(
                                HybridAssertionRequest(
                                    WebAuthnAssertionHashRequest(
                                        "example.com",
                                        ByteArray(32),
                                        if (silent) "discouraged" else "required",
                                        allow,
                                    ),
                                    userPresent = !silent,
                                ),
                            )
                        }
                    try {
                        guard()
                        response is HybridResult.Success
                    } finally {
                        when (val value = (response as? HybridResult.Success)?.value) {
                            is WebAuthnAssertionResult -> {
                                flags = value.authenticatorData[32].toInt() and 0xff
                                value.signature.fill(0)
                                value.prfResults?.clear()
                            }

                            is WebAuthnCredentialCreationResult -> {
                                value.prfResults?.clear()
                            }
                        }
                    }
                },
            )

        fun start() {
            producer.state.value.onScan
                ?.invoke(QR)
            producer.state.value.onPermission
                ?.invoke(true)
        }

        suspend fun phase(phase: HybridPhase) = producer.state.first { it.phase == phase }
    }

    private class Vault : HybridVault {
        var available = true
        var writable = true
        var storeFails = false
        var stored: WebAuthnCredential? = null
        val active = MutableStateFlow(true)

        override suspend fun openSession(): HybridVaultSession =
            object : HybridVaultSession {
                override val active = this@Vault.active

                override fun requireActive() {
                    check(active.value)
                }
            }

        override suspend fun candidates(request: WebAuthnAssertionHashRequest) =
            if (available) listOf(CANDIDATE) else emptyList()

        override suspend fun credential(candidate: HybridCandidate) =
            credential().takeIf { available && candidate == CANDIDATE }

        override suspend fun requireWritable(target: HybridSaveTarget) {
            if (!available || !writable || target != TARGET) throw HybridWriteException()
        }

        override suspend fun credentials() = if (available) listOf(credential()) else emptyList()

        override suspend fun save(
            target: HybridSaveTarget,
            credential: WebAuthnCredential,
        ): Boolean {
            requireWritable(target)
            if (!storeFails) stored = credential
            return !storeFails
        }

        override suspend fun recordUsage(candidate: HybridCandidate) = Unit
    }

    private companion object {
        const val QR =
            "FIDO:/6767604935059062948727942671537211028307910354016841417559286341" +
                "33814168227179318566500564050807297738025421010491811870057950297627316200281471117231106"
        val TARGET = HybridSaveTarget("account", "item")
        val CANDIDATE = HybridCandidate("account", "item", "00000000-0000-0000-0000-000000000001", "Example", "alice")

        fun credential() =
            WebAuthnCredential(
                credentialId = CANDIDATE.credentialId,
                keyType = "public-key",
                keyAlgorithm = "ECDSA",
                keyCurve = "P-256",
                keyValue = "AQ",
                rpId = "example.com",
                discoverable = true,
                userHandle = "dXNlcg",
            )
    }
}
