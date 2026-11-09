package com.artemchep.keyguard.feature.passkeyhybrid

import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.passkey.toWebAuthnCredential
import com.artemchep.keyguard.common.service.vault.VaultSession
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridSaveTarget
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridWriteException
import com.artemchep.keyguard.feature.passkeys.HybridVaultAdapter
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class HybridVaultAdapterTest {
    @Test
    fun visibleCandidatesAndExclusionCredentialsUseDifferentSnapshots() =
        runTest {
            val fixture = Fixture()
            fixture.raw.value =
                listOf(
                    cipher(),
                    cipher().copy(id = "hidden"),
                    cipher().copy(
                        id = "archived",
                        archivedDate = Instant.fromEpochMilliseconds(1),
                    ),
                    cipher().copy(
                        id = "deleted",
                        deletedDate = Instant.fromEpochMilliseconds(1),
                    ),
                )
            assertEquals(listOf("item"), fixture.adapter.candidates(request()).map { it.cipherId })
            assertEquals(3, fixture.adapter.credentials().size)
            val candidate = fixture.adapter.candidates(request()).single()
            fixture.visible.value = emptyList()
            assertEquals(null, fixture.adapter.credential(candidate))
        }

    @Test
    fun leaseRejectsAChangedVaultSession() =
        runTest {
            val fixture = Fixture()
            val lease = assertNotNull(fixture.adapter.openSession())
            lease.requireActive()
            fixture.current.value = MasterSession.Empty()
            assertFailsWith<IllegalStateException> { lease.requireActive() }
            assertTrue(lease.active.value)
        }

    @Test
    fun readOnlyCollectionAndRemovedTargetsCannotBeSaved() =
        runTest {
            val fixture = Fixture()
            val target = HybridSaveTarget("account", "item")
            fixture.raw.value = listOf(cipher().copy(collectionIds = setOf("collection")))
            fixture.collections.value =
                listOf(
                    DCollection(
                        "collection",
                        null,
                        null,
                        "account",
                        Instant.fromEpochMilliseconds(0),
                        "Shared",
                        readOnly = true,
                        hidePasswords = false,
                    ),
                )
            assertFailsWith<HybridWriteException> { fixture.adapter.save(target, credential()) }
            fixture.collections.value = emptyList()
            fixture.adapter.requireWritable(target)
            fixture.raw.value = emptyList()
            assertFailsWith<HybridWriteException> { fixture.adapter.save(target, credential()) }
            assertEquals(0, fixture.writes)
        }

    @Test
    fun onlyConfirmedStorageReturnsSuccess() =
        runTest {
            val fixture = Fixture()
            val target = HybridSaveTarget("account", "item")
            fixture.rejectStorage = true
            assertEquals(false, fixture.adapter.save(target, credential()))
            fixture.rejectStorage = false
            assertEquals(true, fixture.adapter.save(target, credential()))
            assertEquals(1, fixture.writes)
        }

    private class Fixture {
        var writes = 0
        var rejectStorage = false
        val raw = MutableStateFlow(listOf(cipher()))
        val visible = MutableStateFlow(listOf(cipher()))
        val collections = MutableStateFlow(emptyList<DCollection>())
        val active = MutableStateFlow(true)
        val current =
            MutableStateFlow<MasterSession>(
                MasterSession.Key(
                    MasterKey(MasterKdfVersion.LATEST, ByteArray(32)),
                    object : VaultSession {
                        override val id = "test"
                        override val active = this@Fixture.active

                        override fun retire() {
                            active.value = false
                        }

                        override fun close() = retire()
                    },
                    MasterSession.Key.Authenticated,
                    Instant.fromEpochMilliseconds(0),
                ),
            )
        val adapter =
            HybridVaultAdapter(
                getSession =
                    object : GetVaultSession {
                        override val valueOrNull get() = current.value

                        override fun invoke() = current
                    },
                visibleCiphers = visible,
                ciphers = raw,
                canWrite = MutableStateFlow(true),
                collections = collections,
                addCredential =
                    object : AddCredentialCipher {
                        override fun invoke(request: AddCredentialCipherRequest) =
                            ioEffect {
                                check(!rejectStorage)
                                writes++
                                AddCredentialCipher.Result.Stored
                            }
                    },
                recordUsage =
                    object : AddCipherUsedPasskeyHistory {
                        override fun invoke(request: AddCipherUsedPasskeyHistoryRequest) = ioEffect { Unit }
                    },
            )
    }

    private companion object {
        fun request() =
            WebAuthnAssertionHashRequest(
                "example.com",
                ByteArray(32),
                "required",
                WebAuthnAllowedCredentialDescriptors.NONE,
            )

        fun credential() = requireNotNull(cipher().login).fido2Credentials.single().toWebAuthnCredential()

        fun cipher() =
            DSecret(
                id = "item",
                accountId = "account",
                folderId = null,
                organizationId = null,
                collectionIds = emptySet(),
                revisionDate = Instant.fromEpochMilliseconds(0),
                createdDate = null,
                archivedDate = null,
                deletedDate = null,
                service = BitwardenService(),
                name = "Example",
                notes = "",
                favorite = false,
                reprompt = false,
                synced = true,
                type = DSecret.Type.Login,
                login =
                    DSecret.Login(
                        fido2Credentials =
                            listOf(
                                DSecret.Login.Fido2Credentials(
                                    credentialId = "00000000-0000-0000-0000-000000000001",
                                    keyType = "public-key",
                                    keyAlgorithm = "ECDSA",
                                    keyCurve = "P-256",
                                    keyValue = "AQ",
                                    rpId = "example.com",
                                    rpName = "Example",
                                    counter = 0,
                                    userHandle = "dXNlcg",
                                    userName = "alice",
                                    userDisplayName = "Alice",
                                    discoverable = true,
                                    creationDate = Instant.fromEpochMilliseconds(0),
                                ),
                            ),
                    ),
            )
    }
}
