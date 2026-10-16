package com.artemchep.keyguard.common.service.gpgagent.impl

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyInfoRow
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadataKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentOperation
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentRequestProcessor.GpgAgentOperationResult
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture as Fixture
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.KEYGRIP
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.caller
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.cipherFilter
import com.artemchep.keyguard.common.service.gpgagent.impl.GpgAgentRequestTestFixture.Companion.createGpgCipher
import com.artemchep.keyguard.common.service.gpgagent.routableAgentKeys
import com.artemchep.keyguard.test.gpgMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

@Suppress("TooManyFunctions")
class GpgAgentDuplicateKeysTest {
    @Test
    fun `locked and unlocked discovery combine complementary copies identically`() = runTest {
        val fixture = Fixture(this)
        val candidates = listOf(
            duplicateCipher("0-public", privateKey = false),
            duplicateCipher("signer", capabilities = setOf("sign")),
            duplicateCipher("decryptor", capabilities = setOf("decrypt")),
        )
        fixture.seed(candidates)
        val expected = fixture.listKeys()
        assertTrue(expected.single().canSign)
        assertTrue(expected.single().canDecrypt)

        for (locked in listOf(false, true)) {
            if (locked) fixture.lock()
            listOf(candidates, candidates.reversed()).forEach { ordered ->
                fixture.seed(ordered)
                assertEquals(expected, fixture.listKeys(), "locked=$locked")
            }
        }
    }

    @Test
    fun `duplicate sources are selected by operation and stable identity while locked or unlocked`() = runTest {
        val signer = duplicateCipher("b", capabilities = setOf("sign"))
        val decryptor = duplicateCipher("c", capabilities = setOf("decrypt"))
        val candidates = listOf(
            duplicateCipher("0-public", privateKey = false),
            duplicateCipher("a", accountId = "z-account", capabilities = setOf("sign")),
            duplicateCipher("z", capabilities = setOf("sign")),
            decryptor,
            signer,
        )
        GpgAgentOperation.entries.forEach { operation ->
            val expected = if (operation == GpgAgentOperation.SIGN) signer else decryptor
            for (locked in listOf(false, true)) {
                listOf(candidates, candidates.reversed()).forEach { ordered ->
                    val fixture = Fixture(this)
                    fixture.seed(ordered)
                    if (locked) {
                        val unlock = fixture.lock()
                        fixture.onApproval = {
                            unlock()
                            true
                        }
                    }
                    fixture.succeed(operation, caller(connection = 1))
                    val prompt = fixture.prompts.single()
                    assertEquals(expected.accountId, prompt.accountId)
                    assertEquals(expected.id, prompt.cipherId)
                    assertEquals(listOf(expected.gpgKey?.privateKeyArmored), fixture.crypto.privateKeys)
                    assertEquals(expected.id, fixture.history.single().cipherId)
                }
            }
        }
    }

    @Test
    fun `a duplicate added during unlock does not replace the approved cached source`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val approved = duplicateCipher("b")
            fixture.seed(listOf(approved))
            val unlock = fixture.lock()
            fixture.onApproval = {
                fixture.ciphers.value = listOf(duplicateCipher("a"), approved)
                unlock()
                true
            }

            fixture.succeed(operation, caller(connection = 1))
            assertEquals(approved.id, fixture.prompts.single().cipherId)
            assertEquals(listOf(approved.gpgKey?.privateKeyArmored), fixture.crypto.privateKeys)
            assertEquals(approved.id, fixture.history.single().cipherId)
        }
    }

    @Test
    fun `an approved duplicate becoming ineligible during approval does not fall back to another item`() = runTest {
        val approved = duplicateCipher("a")
        val backup = duplicateCipher("b")
        GpgAgentOperation.entries.forEach { operation ->
            for ((change, mutate) in ineligibleChanges(approved, backup)) {
                val fixture = Fixture(this)
                fixture.seed(listOf(approved, backup))
                val result = fixture.finishPendingApproval(operation) { fixture.mutate() }
                assertIs<GpgAgentOperationResult.KeyNotFound>(result, "$operation $change")
                assertEquals(0, fixture.crypto.calls)
                assertEquals(approved.id, fixture.history.single().cipherId)
            }
        }
    }

    @Test
    fun `a locked request asks again when the catalog's choice no longer works`() = runTest {
        val approved = duplicateCipher("a")
        val backup = duplicateCipher("b")
        GpgAgentOperation.entries.forEach { operation ->
            val changes = ineligibleChanges(approved, backup).toMutableMap()
            // The catalog keeps the signing authorization of its last sync.
            if (operation == GpgAgentOperation.SIGN) changes["revoked"] = { revokedCipherIds += approved.id }
            for ((change, mutate) in changes) {
                for (approveAgain in listOf(true, false)) {
                    val fixture = Fixture(this)
                    fixture.seed(listOf(approved, backup))
                    val unlock = fixture.lock()
                    fixture.onApproval = {
                        if (fixture.approvals == 1) {
                            fixture.mutate()
                            unlock()
                            true
                        } else {
                            approveAgain
                        }
                    }

                    val result = fixture.request(operation, caller(connection = 1))
                    val label = "$operation $change approveAgain=$approveAgain"
                    assertEquals(listOf(approved.id, backup.id), fixture.prompts.map { it.cipherId }, label)
                    assertEquals(backup.id, fixture.history.single().cipherId, label)
                    if (approveAgain) {
                        assertIs<GpgAgentOperationResult.Success<*>>(result, label)
                        assertEquals(listOf(backup.gpgKey?.privateKeyArmored), fixture.crypto.privateKeys, label)
                    } else {
                        assertIs<GpgAgentOperationResult.UserDenied>(result, label)
                        assertEquals(0, fixture.crypto.calls, label)
                    }
                }
            }
        }
    }

    @Test
    fun `denial and crypto failure do not try another duplicate`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            for (deny in listOf(false, true)) {
                val fixture = Fixture(this)
                fixture.ciphers.value = listOf(duplicateCipher("a"), duplicateCipher("b"))
                fixture.onApproval = { !deny }
                fixture.crypto.fail = true

                val result = fixture.request(operation, caller(connection = 1))
                if (deny) {
                    assertIs<GpgAgentOperationResult.UserDenied>(result)
                    assertEquals(0, fixture.crypto.calls)
                } else {
                    assertIs<GpgAgentOperationResult.Failure>(result)
                    assertEquals(1, fixture.crypto.calls)
                }
                assertEquals(1, fixture.approvals)
                assertEquals("a", fixture.history.single().cipherId)
            }
        }
    }

    @Test
    fun `discovery and signing use each copy's current authorization`() = runTest {
        val fixture = Fixture(this)
        fixture.ciphers.value = listOf(
            duplicateCipher("a", capabilities = setOf("sign")),
            duplicateCipher("b", capabilities = setOf("sign")),
            duplicateCipher("0-public", privateKey = false),
        )
        fixture.revokedCipherIds += "a"
        assertTrue(fixture.listKeys().single().canSign)
        fixture.succeed(GpgAgentOperation.SIGN, caller(connection = 1))
        assertEquals("b", fixture.prompts.single().cipherId)

        fixture.revokedCipherIds += "b"
        assertFalse(fixture.listKeys().single().canSign)
        assertIs<GpgAgentOperationResult.KeyNotFound>(fixture.request(GpgAgentOperation.SIGN, caller(connection = 2)))
        assertEquals(1, fixture.crypto.calls)
    }

    @Test
    fun `archived trashed and filtered duplicates do not contribute capabilities`() = runTest {
        val fixture = Fixture(this)
        val publicOnly = duplicateCipher("public", privateKey = false)
        val privateKey = duplicateCipher("private")
        val inactiveCopies = listOf(
            privateKey.copy(archivedDate = INACTIVE_DATE),
            privateKey.copy(deletedDate = INACTIVE_DATE),
        )
        for (inactive in inactiveCopies) {
            fixture.ciphers.value = listOf(inactive, publicOnly)
            val key = fixture.listKeys().single()
            assertFalse(key.canSign)
            assertFalse(key.canDecrypt)
        }
        fixture.ciphers.value = listOf(privateKey, publicOnly)
        fixture.filter.value = cipherFilter(publicOnly.id)
        val key = fixture.listKeys().single()
        assertFalse(key.canSign)
        assertFalse(key.canDecrypt)
    }

    @Test
    fun `remembered approvals remain key scoped when a later request selects another copy`() = runTest {
        GpgAgentOperation.entries.forEach { operation ->
            val fixture = Fixture(this)
            val first = duplicateCipher("a")
            val second = duplicateCipher("b")
            fixture.ciphers.value = listOf(first, second)
            fixture.succeed(operation, caller(connection = 1))
            fixture.ciphers.value = listOf(second)
            fixture.succeed(operation, caller(connection = 2))

            assertEquals(1, fixture.approvals)
            assertEquals(
                listOf(first.gpgKey?.privateKeyArmored, second.gpgKey?.privateKeyArmored),
                fixture.crypto.privateKeys,
            )
            assertEquals(listOf(first.id, second.id), fixture.history.map { it.cipherId })
        }
    }

    private fun duplicateCipher(
        id: String,
        accountId: String = "account",
        capabilities: Set<String> = setOf("sign", "decrypt"),
        privateKey: Boolean = true,
    ): DSecret {
        val original = createGpgCipher(KEYGRIP)
        return original.copy(
            id = id,
            name = id,
            accountId = accountId,
            gpgKey = requireNotNull(original.gpgKey).copy(
                publicKeyArmored = "public:$accountId:$id",
                privateKeyArmored = if (privateKey) "private:$accountId:$id" else null,
                metadata = gpgMetadata(GpgAgentKeyMetadataKey(KEYGRIP, KEYGRIP, "RSA", capabilities)),
            ),
        )
    }

    /** Changes that make [approved] unusable while [backup] stays eligible. */
    private fun ineligibleChanges(
        approved: DSecret,
        backup: DSecret,
    ) = mapOf<String, Fixture.() -> Unit>(
        "removed" to { ciphers.value = listOf(backup) },
        "archived" to { ciphers.value = listOf(approved.copy(archivedDate = INACTIVE_DATE), backup) },
        "filtered" to { filter.value = cipherFilter(backup.id) },
    )

    /** Stores the ciphers in the vault and in the catalog the syncer would persist for them. */
    private fun Fixture.seed(ciphers: List<DSecret>) {
        this.ciphers.value = ciphers
        cachedKeys = ciphers.map { it.cachedKey() }
    }

    private fun DSecret.cachedKey(): GpgAgentKeyInfoRow {
        val material = requireNotNull(gpgKey)
        val key = requireNotNull(material.metadata).routableAgentKeys.single()
        return GpgAgentKeyInfoRow(
            accountId = accountId,
            cipherId = id,
            keygrip = key.keygrip,
            fingerprint = key.fingerprint,
            algorithm = key.algorithm,
            canSign = material.privateKeyArmored != null && key.canSign,
            canDecrypt = material.privateKeyArmored != null && key.canDecrypt,
            name = name,
        )
    }

    private companion object {
        val INACTIVE_DATE = Instant.parse("2024-02-01T00:00:00Z")
    }
}
