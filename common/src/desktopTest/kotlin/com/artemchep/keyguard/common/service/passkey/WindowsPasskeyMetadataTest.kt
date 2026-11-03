package com.artemchep.keyguard.common.service.passkey

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyDescriptor
import com.artemchep.keyguard.util.windowspasskeys.WindowsPasskeyIdentity
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class WindowsPasskeyMetadataTest {
    @Test
    fun importedPasskeysUseAvailableNamesWithoutInventingUserHandles() {
        val credential = testPasskey()
        val source = testCipher(credential)
        val identity = windowsPasskeyIdentities(listOf(source)).single()
        assertEquals("login@example.com", identity.userName)
        assertEquals("example.com", identity.rpName)
        assertContentEquals(TEST_USER_HANDLE, identity.userId)
        val withoutUsername = source.copy(login = source.login!!.copy(username = null))
        assertEquals("Imported login", windowsPasskeyIdentities(listOf(withoutUsername)).single().userName)
        assertTrue(windowsPasskeyIdentities(listOf(testCipher(credential.copy(userHandle = null)))).isEmpty())
        // Any Base64 form of the stored handle reaches Windows as the same canonical bytes.
        val padded = testCipher(credential.copy(userHandle = "dXNlcg=="))
        assertContentEquals(TEST_USER_HANDLE, windowsPasskeyIdentities(listOf(padded)).single().userId)
    }

    @Test
    fun cacheExcludesDeletedArchivedNonDiscoverableAndMalformedEntries() {
        val credential = testPasskey()
        val valid = testCipher(credential)
        val excluded = listOf(
            valid.copy(deletedDate = TEST_NOW), valid.copy(archivedDate = TEST_NOW),
            testCipher(credential.copy(discoverable = false)), testCipher(credential.copy(keyAlgorithm = "RSA")),
            testCipher(credential.copy(userHandle = "!")),
            testCipher(credential.copy(userHandle = PasskeyBase64.encodeToString(ByteArray(65)))),
            testCipher(credential.copy(rpId = "example\u0000.com")),
        )
        assertTrue(windowsPasskeyIdentities(excluded).isEmpty())
        assertEquals(1, windowsPasskeyIdentities(excluded + valid + valid.copy(accountId = "synced-copy")).size)
    }

    @Test
    fun firstValidDuplicateWins() {
        val credential = testPasskey()
        val first = testCipher(credential.copy(userName = "first"))
        val second = testCipher(credential.copy(userName = "second"))
        val invalid = testCipher(credential.copy(rpId = " "))
        assertEquals("first", windowsPasskeyIdentities(listOf(invalid, first, second)).single().userName)
        assertEquals("second", windowsPasskeyIdentities(listOf(second, first)).single().userName)
    }

    @Test
    fun allowListDistinguishesAbsentAndUnsupportedDescriptorTypes() {
        val stored = testPasskey().toWebAuthnCredential()
        val request = testSignInRequest().copy(clientDataHash = byteArrayOf(), discoverable = true)
        assertTrue(request.allowed().allows(stored))
        assertFalse(request.allowed().allows(stored.copy(discoverable = false)))
        val descriptor = WindowsPasskeyDescriptor(PasskeyCredentialId.encode(stored.credentialId), "unknown")
        assertFalse(request.copy(credentials = listOf(descriptor)).allowed().allows(stored))
        val publicKeyRequest = request.copy(credentials = listOf(descriptor.copy(type = "public-key")))
        assertTrue(publicKeyRequest.allowed().allows(stored.copy(discoverable = false)))
    }

    @Test
    fun indexWaitsForBurstsAndSkipsUnrelatedChanges() = runTest {
        val vault = MutableSharedFlow<List<DSecret>>()
        val received = mutableListOf<List<WindowsPasskeyIdentity>>()
        backgroundScope.launch { vault.windowsPasskeyIndex().collect { received += it } }
        runCurrent()
        // A sync burst; renaming the item does not change its passkey's public identity.
        repeat(5) {
            vault.emit(listOf(testCipher(testPasskey()).copy(name = "Edit $it")))
            advanceTimeBy(100)
        }
        assertTrue(received.isEmpty())
        advanceTimeBy(1_000)
        assertEquals(1, received.size)

        vault.emit(listOf(testCipher(testPasskey()).copy(name = "Unrelated")))
        advanceTimeBy(2_000)
        assertEquals(1, received.size)

        vault.emit(listOf(cipherWithUsername("renamed@example.com")))
        advanceTimeBy(2_000)
        assertEquals("renamed@example.com", received.last().single().userName)
    }

    @Test
    fun slowSyncNeverHoldsBackTheVaultAndReceivesOnlyTheNewestIndex() = runTest {
        // No buffer: an emit suspends until the index pipeline takes the value.
        val vault = MutableSharedFlow<List<DSecret>>()
        val nativeSync = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        backgroundScope.launch {
            vault.windowsPasskeyIndex().collect { index ->
                received += index.single().userName
                nativeSync.await()
            }
        }
        runCurrent()
        for (username in listOf("first", "second", "third")) {
            vault.emit(listOf(cipherWithUsername(username)))
            advanceTimeBy(2_000)
        }
        nativeSync.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "third"), received)
    }

    private fun cipherWithUsername(username: String) = testCipher(testPasskey()).let {
        it.copy(login = it.login!!.copy(username = username))
    }
}
