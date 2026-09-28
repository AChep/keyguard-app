package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.appleKoinModules
import com.artemchep.keyguard.common.service.directorywatcher.FileWatcherService
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.copy.FileWatcherServiceApple
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.di.resolveOrCancel
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.platform.util.hasAutofill
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class AutofillMetadataTest {
    private val now = Instant.fromEpochSeconds(1)
    private val uri = BitwardenCipher.Login.Uri(uri = "https://example.test")
    private fun cipher(login: BitwardenCipher.Login) = BitwardenCipher(
        accountId = "account", cipherId = "item", revisionDate = now,
        service = BitwardenService(), name = "Login", notes = null, favorite = false,
        reprompt = BitwardenCipher.RepromptType.None, type = BitwardenCipher.Type.Login, login = login,
    )
    private fun passkey(id: String?) = BitwardenCipher.Login.Fido2Credentials(
        credentialId = id, keyType = "public-key", keyAlgorithm = "ECDSA", keyCurve = "P-256",
        keyValue = "", rpId = "example.test", rpName = null, counter = "0",
        discoverable = "true", creationDate = now,
    )

    @Test
    fun extensionSessionDefersMutationSyncWithoutResolvingNetworkServices() = kotlinx.coroutines.test.runTest {
        // The extension session must stay usable without instantiating any
        // networking, sync or licensing service: both queue use cases resolve to
        // no-ops in the AutoFill runtime.
        val koin = koinApplication {
            allowOverride(true)
            modules(
                appleKoinModules() + module {
                    single<com.artemchep.keyguard.platform.AppleSessionMode> {
                        com.artemchep.keyguard.platform.AppleSessionMode.AUTOFILL
                    }
                    single<com.artemchep.keyguard.common.usecase.GetLicensePremium> {
                        object : com.artemchep.keyguard.common.usecase.GetLicensePremium {
                            override fun invoke() = kotlinx.coroutines.flow.flowOf(false)
                        }
                    }
                },
            )
        }.koin
        try {
            val session = koin.get<com.artemchep.keyguard.common.service.vault.VaultSessionFactory>().create(
                com.artemchep.keyguard.common.model.MasterKey(
                    com.artemchep.keyguard.common.model.MasterKdfVersion.V1, ByteArray(32),
                ),
            )
            try {
                session.resolveOrCancel { get<com.artemchep.keyguard.common.usecase.QueueSyncById>() }(
                    com.artemchep.keyguard.common.model.AccountId("account"),
                ).bind()
                session.resolveOrCancel { get<com.artemchep.keyguard.common.usecase.QueueSyncAll>() }().bind()
                val watcher = session.resolveOrCancel { get<FileWatcherService>() }
                assertFalse(watcher is FileWatcherServiceApple)
                val events = withTimeout(1_000L) {
                    watcher.uriChangedFlow(
                        uri = "file:///tmp/autofill.kdbx",
                        accessToken = FileAccessToken("invalid-bookmark"),
                    ).toList()
                }
                assertTrue(events.isEmpty())
            } finally {
                session.close()
            }
        } finally {
            koin.close()
        }
    }

    @Test
    fun releaseCapabilitiesIncludeNativeAppleButNotJvmMacOrUnsupportedAndroidDevices() {
        assertTrue(Platform.Mobile.Ios.Native.hasAutofill())
        assertTrue(Platform.Desktop.MacOS.Native.hasAutofill())
        assertFalse(Platform.Desktop.MacOS.Jvm.hasAutofill())
        assertTrue(Platform.Mobile.Android(false, false, 35).hasAutofill())
        assertFalse(Platform.Mobile.Android(true, false, 35).hasAutofill())
        assertFalse(Platform.Mobile.Android(false, true, 35).hasAutofill())
    }

    @Test
    fun deletedAndHiddenAccountsCannotResolveOrPublishCredentials() {
        val cipher = cipher(BitwardenCipher.Login(uris = listOf(uri), password = "password"))
        assertTrue(cipher.isAutofillEligible(emptySet()))
        assertFalse(cipher.copy(deletedDate = now).isAutofillEligible(emptySet()))
        assertFalse(cipher.copy(service = cipher.service.copy(deleted = true)).isAutofillEligible(emptySet()))
        assertFalse(cipher.isAutofillEligible(setOf("account")))
    }

    @Test
    fun neverUrisAndDuplicatesAreNotPublished() {
        val cipher = cipher(BitwardenCipher.Login(uris = listOf(
            uri, uri, uri.copy(uri = "https://excluded.test", match = BitwardenCipher.Login.Uri.MatchType.Never),
            uri.copy(uri = null), uri.copy(uri = " "),
        ), password = "password"))
        val identities = listOf(cipher).toAutofillIndex().passwords
        assertEquals(listOf("https://example.test"), identities.map { it.serviceIdentifier })
        assertEquals("account|item", identities.single().recordId)
    }

    @Test
    fun passkeyOnlyLoginIsNotAnEmptyPasswordSuggestion() {
        val cipher = cipher(BitwardenCipher.Login(uris = listOf(uri), fido2Credentials = listOf(
            passkey("59e2144e-f203-48dd-8aa1-65986c24d87d"),
        )))
        val snapshot = listOf(cipher).toAutofillIndex()
        assertTrue(snapshot.passwords.isEmpty())
        assertEquals(1, snapshot.passkeys.size)
        assertEquals(0, snapshot.skippedPasskeys)
    }

    @Test
    fun unparseableTotpIsExcludedFromBothManualPickerAndSystemIndex() {
        val invalid = cipher(BitwardenCipher.Login(uris = listOf(uri), totp = "otpauth://totp/Example?digits=invalid"))
        val valid = cipher(BitwardenCipher.Login(uris = listOf(uri), totp = "JBSWY3DPEHPK3PXP"))
        assertFalse(invalid.hasAutofillOneTimeCode())
        assertFalse(cipher(BitwardenCipher.Login(uris = listOf(uri), totp = " ")).hasAutofillOneTimeCode())
        assertTrue(valid.hasAutofillOneTimeCode())
        assertEquals(1, listOf(invalid, valid).toAutofillIndex().oneTimeCodes.size)
    }

    @Test
    fun malformedPasskeyCannotBlockValidPasswordAndPasskeyIndexing() {
        val cipher = cipher(BitwardenCipher.Login(uris = listOf(uri), password = "password", fido2Credentials = listOf(
            passkey(null), passkey("not-a-uuid").copy(userHandle = "%%%"),
            passkey("59e2144e-f203-48dd-8aa1-65986c24d87d"),
        )))
        val snapshot = listOf(cipher).toAutofillIndex()
        assertEquals(1, snapshot.passwords.size)
        assertEquals(1, snapshot.passkeys.size)
        assertEquals(2, snapshot.skippedPasskeys)
    }

    @Test
    fun passkeyPickerRequiresDiscoverabilityUnlessCredentialIsExplicitlyAllowed() {
        val id = "59e2144e-f203-48dd-8aa1-65986c24d87d"
        val cipher = cipher(BitwardenCipher.Login(uris = listOf(uri), fido2Credentials = listOf(
            passkey(id).copy(discoverable = "false"),
        )))
        val ciphers = listOf(cipher)
        assertTrue(ciphers.toPasskeyIdentities().isEmpty())
        val allowed = WebAuthnAllowedCredentialDescriptors.fromCredentialIds(listOf(PasskeyCredentialId.encode(id)))
        assertEquals(1, ciphers.toPasskeyIdentities(rpId = "example.test", allowedCredentials = allowed).size)
        assertTrue(ciphers.toPasskeyIdentities(rpId = "other.test", allowedCredentials = allowed).isEmpty())
        val unrelated = WebAuthnAllowedCredentialDescriptors.fromCredentialIds(listOf(byteArrayOf(1)))
        assertTrue(ciphers.toPasskeyIdentities(rpId = "example.test", allowedCredentials = unrelated).isEmpty())
    }
}
