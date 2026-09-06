package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.RefreshGpgPublicKeysResult
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentFields
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GpgKeyserverRefreshConcurrentChangeTest {
    @Test
    fun `invalidated targets keep their current row and do not report success`() = runTest {
        val changes: Map<String, (BitwardenCipher) -> BitwardenCipher> = mapOf(
            "key removed" to { it.copy(gpgKey = null) },
            "key cleared" to { it.copy(gpgKey = BitwardenCipher.GpgKey(), fields = emptyList()) },
            "fingerprint changed" to { it.copy(gpgKey = it.gpgKey?.copy(fingerprint = "A".repeat(40))) },
            "secret added" to { it.copy(gpgKey = it.gpgKey?.copy(privateKeyArmored = "new secret")) },
            "legacy secret added" to {
                it.copy(
                    fields = listOf(
                        BitwardenCipher.Field(
                            name = GpgAgentFields.PRIVATE_KEY_ARMORED,
                            value = "new legacy secret",
                            type = BitwardenCipher.Field.Type.Hidden,
                        ),
                    ),
                )
            },
            "account changed" to { it.copy(accountId = "another-account") },
            "trashed" to { it.copy(deletedDate = REFRESH_CREATED_AT) },
            "deleted" to { it.copy(service = it.service.copy(deleted = true)) },
            "decode failed" to {
                it.copy(
                    service = it.service.copy(
                        error = BitwardenService.Error(
                            code = BitwardenService.Error.CODE_DECODING_FAILED,
                            revisionDate = REFRESH_CREATED_AT,
                        ),
                    ),
                )
            },
        )
        for (response in lookupResponses) {
            for ((changeLabel, change) in changes) {
                val label = "$changeLabel (found=${response != null})"
                GpgKeyserverRefreshTestFixture().use { fixture ->
                    val expected = change(fixture.row().data_)
                    fixture.lookup = { response }
                    fixture.beforeLookup = {
                        fixture.update(change)
                        assertEquals(expected, fixture.row().data_, label)
                    }

                    assertEquals(RefreshGpgPublicKeysResult(0, 0, 0, 1), fixture.useCase(fixture.request).bind(), label)
                    assertEquals(expected, fixture.row().data_, label)
                    assertTrue(fixture.lastRefreshes.isEmpty())
                    assertTrue(fixture.stateRepository.getAll().first().isEmpty())
                }
            }
        }
    }

    @Test
    fun `malformed material added during lookup is not discarded`() = runTest {
        GpgKeyserverRefreshTestFixture().use { fixture ->
            val expected = fixture.row().data_.let {
                it.copy(gpgKey = it.gpgKey?.copy(publicKeyArmored = "malformed material"))
            }
            fixture.beforeLookup = { fixture.update { expected } }

            assertEquals(RefreshGpgPublicKeysResult(0, 0, 0, 1), fixture.useCase(fixture.request).bind())
            assertEquals(expected, fixture.row().data_)
            assertTrue(fixture.lastRefreshes.isEmpty())
            assertTrue(fixture.stateRepository.getAll().first().isEmpty())
        }
    }

    @Test
    fun `a removed row is not recreated by its pending refresh`() = runTest {
        for (response in lookupResponses) {
            GpgKeyserverRefreshTestFixture().use { fixture ->
                fixture.lookup = { response }
                fixture.beforeLookup = { fixture.database.cipherQueries.deleteByCipherId(REFRESH_CIPHER_ID) }

                assertEquals(RefreshGpgPublicKeysResult(0, 0, 0, 1), fixture.useCase(fixture.request).bind())
                assertEquals(0, fixture.database.cipherQueries.get().executeAsList().size)
                assertTrue(fixture.lastRefreshes.isEmpty())
                assertTrue(fixture.stateRepository.getAll().first().isEmpty())
            }
        }
    }

    @Test
    fun `unrelated edits are preserved without rejecting an otherwise valid refresh`() = runTest {
        GpgKeyserverRefreshTestFixture().use { fixture ->
            fixture.beforeLookup = { fixture.update { it.copy(notes = "Edited while downloading", favorite = true) } }

            assertEquals(RefreshGpgPublicKeysResult(1, 0, 0), fixture.useCase(fixture.request).bind())
            assertEquals("Edited while downloading", fixture.row().data_.notes)
            assertTrue(fixture.row().data_.favorite)
            assertEquals(0, fixture.backupDirtyCount)
        }
    }

    @Test
    fun `legacy field-backed keys retain their local material on refresh`() = runTest {
        val legacy = refreshTestCipher().copy(
            type = BitwardenCipher.Type.SecureNote,
            gpgKey = null,
            fields = listOf(
                BitwardenCipher.Field(
                    name = GpgAgentFields.PUBLIC_KEY_ARMORED,
                    value = REFRESH_PUBLIC_KEY,
                    type = BitwardenCipher.Field.Type.Hidden,
                ),
                BitwardenCipher.Field(
                    name = GpgAgentFields.FINGERPRINT,
                    value = REFRESH_FINGERPRINT,
                    type = BitwardenCipher.Field.Type.Text,
                ),
            ),
        )
        val storedKeys = listOf(
            null,
            BitwardenCipher.GpgKey(),
            BitwardenCipher.GpgKey(privateKeyArmored = "", publicKeyArmored = "", fingerprint = ""),
            BitwardenCipher.GpgKey(privateKeyArmored = " \n\t", publicKeyArmored = " \n\t", fingerprint = " \n\t"),
        )
        for (storedKey in storedKeys) {
            GpgKeyserverRefreshTestFixture(initial = listOf(legacy.copy(gpgKey = storedKey))).use { fixture ->

                assertEquals(RefreshGpgPublicKeysResult(1, 0, 0), fixture.useCase(fixture.request).bind())
                assertEquals(REFRESH_PUBLIC_KEY, fixture.row().data_.gpgKey?.publicKeyArmored)
                assertEquals(REFRESH_FINGERPRINT, fixture.row().data_.gpgKey?.fingerprint)
                assertEquals(legacy.fields, fixture.row().data_.fields)
            }
        }
    }

    @Test
    fun `blank native private values never hide legacy secret material`() = runTest {
        for (privateKey in listOf(null, "", " \n\t")) {
            val initial = refreshTestCipher().let { cipher ->
                cipher.copy(gpgKey = cipher.gpgKey?.copy(privateKeyArmored = privateKey))
            }
            val withLegacySecret = initial.copy(
                fields = listOf(
                    BitwardenCipher.Field(
                        name = GpgAgentFields.PRIVATE_KEY_ARMORED,
                        value = "legacy secret",
                        type = BitwardenCipher.Field.Type.Hidden,
                    ),
                ),
            )
            GpgKeyserverRefreshTestFixture(initial = listOf(withLegacySecret)).use { skipped ->
                assertEquals(RefreshGpgPublicKeysResult(0, 0, 1), skipped.useCase(skipped.request).bind())
                assertTrue(skipped.lookups.isEmpty())
            }

            GpgKeyserverRefreshTestFixture(initial = listOf(initial)).use { fixture ->
                fixture.beforeLookup = { fixture.update { withLegacySecret } }

                assertEquals(RefreshGpgPublicKeysResult(0, 0, 0, 1), fixture.useCase(fixture.request).bind())
                assertEquals(withLegacySecret, fixture.row().data_)
                assertTrue(fixture.lastRefreshes.isEmpty())
                assertTrue(fixture.stateRepository.getAll().first().isEmpty())
            }
        }
    }

    @Test
    fun `secret-backed and deleted items are skipped before lookup`() = runTest {
        val key = refreshTestCipher()
        val ineligible = listOf(
            key.copy(gpgKey = key.gpgKey?.copy(privateKeyArmored = "secret")),
            key.copy(gpgKey = key.gpgKey?.copy(publicKeyArmored = "")),
            key.copy(deletedDate = REFRESH_CREATED_AT),
            key.copy(service = key.service.copy(deleted = true)),
        )
        for (cipher in ineligible) {
            GpgKeyserverRefreshTestFixture(initial = listOf(cipher)).use { fixture ->

                assertEquals(RefreshGpgPublicKeysResult(0, 0, 1), fixture.useCase(fixture.request).bind())
                assertTrue(fixture.lookups.isEmpty())
                assertTrue(fixture.lastRefreshes.isEmpty())
            }
        }
    }

    @Test
    fun `an account filter excludes keys from another account`() = runTest {
        GpgKeyserverRefreshTestFixture().use { fixture ->

            val result = fixture.useCase(fixture.request.copy(accountId = "other")).bind()

            assertEquals(RefreshGpgPublicKeysResult(0, 0, 1), result)
            assertTrue(fixture.lookups.isEmpty())
        }
    }

    private val lookupResponses = listOf(
        DGpgKeyserverResult(REFRESH_FINGERPRINT, publicKeyArmored = REFRESH_PUBLIC_KEY),
        null,
    )
}
