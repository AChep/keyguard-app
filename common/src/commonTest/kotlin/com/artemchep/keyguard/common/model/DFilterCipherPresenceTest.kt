package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.feature.home.vault.search.TEST_INSTANT
import com.artemchep.keyguard.feature.home.vault.search.createSecret
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.DirectDI
import org.kodein.di.direct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DFilterCipherPresenceTest {
    private val totp = DSecret.Login.Totp(
        raw = "JBSWY3DPEHPK3PXP",
        token = TotpToken.parse("JBSWY3DPEHPK3PXP").getOrNull()!!,
    )

    private val fido2Credential = DSecret.Login.Fido2Credentials(
        credentialId = "cred-id",
        keyType = "public-key",
        keyAlgorithm = "ECDSA",
        keyCurve = "P-256",
        keyValue = "key-value",
        rpId = "example.com",
        rpName = null,
        counter = null,
        userHandle = "user-handle",
        userName = null,
        userDisplayName = null,
        discoverable = true,
        creationDate = TEST_INSTANT,
    )

    private val attachment = DSecret.Attachment.Local(
        id = "attachment-id",
        url = "https://example.com/file",
        fileName = "file.txt",
    )

    // A service whose error is still relevant for the fixture revision date,
    // so error.exists(revisionDate) == true.
    private val errorService = BitwardenService(
        error = BitwardenService.Error(
            code = BitwardenService.Error.CODE_UNKNOWN,
            revisionDate = TEST_INSTANT,
        ),
    )

    // Cipher exhibiting every "present" polarity.
    private val cipherA = createSecret(
        id = "a",
        accountId = "acc-1",
        folderId = "folder-1",
        organizationId = "org-1",
        collectionIds = setOf("col-1"),
        tags = listOf("tag-1"),
        type = DSecret.Type.Login,
        favorite = true,
        reprompt = true,
        synced = true,
        service = errorService,
        ignoredAlerts = mapOf(DWatchtowerAlertType.WEAK_PASSWORD to TEST_INSTANT),
        attachments = listOf(attachment),
        login = DSecret.Login(
            password = "hunter2",
            passwordStrength = PasswordStrength(
                crackTimeSeconds = 1L,
                score = PasswordStrength.Score.Weak,
                version = 1L,
            ),
            fido2Credentials = listOf(fido2Credential),
            totp = totp,
        ),
    )

    // Cipher exhibiting the opposite polarities (null folder/org, empty
    // tags/collections, not favorite, not reprompt, not synced, no error, no
    // login features).
    private val cipherB = createSecret(
        id = "b",
        accountId = "acc-2",
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        tags = emptyList(),
        type = DSecret.Type.Card,
        favorite = false,
        reprompt = false,
        synced = false,
        service = BitwardenService(),
    )

    private val ciphers = listOf(cipherA, cipherB)

    private val presence = DFilterCipherPresence.of(ciphers) { it }

    private val di: DirectDI = DI {}.direct

    private suspend fun assertEquivalent(
        primitive: DFilter.Primitive,
        ciphers: List<DSecret> = this.ciphers,
        presence: DFilterCipherPresence = this.presence,
    ) {
        val predicate = primitive.prepare(di, ciphers)
        val expected = ciphers.any(predicate)
        val actual = primitive.existsIn(presence)
        assertEquals(
            expected,
            actual,
            "existsIn disagrees with prepare for $primitive",
        )
    }

    @Test
    fun `existsIn matches prepare for every indexable primitive`() = runTest {
        // Every ById.What crossed with a present id, an absent id, and a null id.
        DFilter.ById.What.entries.forEach { what ->
            val presentId = when (what) {
                DFilter.ById.What.ACCOUNT -> "acc-1"
                DFilter.ById.What.FOLDER -> "folder-1"
                DFilter.ById.What.ORGANIZATION -> "org-1"
                DFilter.ById.What.CIPHER -> "a"
                DFilter.ById.What.TAG -> "tag-1"
                DFilter.ById.What.COLLECTION -> "col-1"
            }
            listOf(presentId, "absent-id", null).forEach { id ->
                assertEquivalent(DFilter.ById(id = id, what = what))
            }
        }

        // Types (hits and misses).
        DSecret.Type.entries.forEach { type ->
            assertEquivalent(DFilter.ByType(type = type))
        }

        // Password strength (hit and miss).
        PasswordStrength.Score.entries.forEach { score ->
            assertEquivalent(DFilter.ByPasswordStrength(score = score))
        }

        // Parameterized booleans, both polarities.
        listOf(true, false).forEach { value ->
            assertEquivalent(DFilter.BySync(synced = value))
            assertEquivalent(DFilter.ByReprompt(reprompt = value))
            assertEquivalent(DFilter.ByError(error = value))
        }

        // Object primitives.
        assertEquivalent(DFilter.ByFavorite)
        assertEquivalent(DFilter.ByOtp)
        assertEquivalent(DFilter.ByAttachments)
        assertEquivalent(DFilter.ByPasskeys)
        assertEquivalent(DFilter.ByIgnoredAlerts)
    }

    @Test
    fun `null markers for tags folders and collections`() = runTest {
        // A cipher with empty tags / empty collections / null folder produces a
        // present null marker; a cipher without those does not.
        val withNulls = DFilterCipherPresence.of(listOf(cipherB)) { it }
        assertEquals(true, DFilter.ById(null, DFilter.ById.What.TAG).existsIn(withNulls))
        assertEquals(true, DFilter.ById(null, DFilter.ById.What.COLLECTION).existsIn(withNulls))
        assertEquals(true, DFilter.ById(null, DFilter.ById.What.FOLDER).existsIn(withNulls))

        val withoutNulls = DFilterCipherPresence.of(listOf(cipherA)) { it }
        assertEquals(false, DFilter.ById(null, DFilter.ById.What.TAG).existsIn(withoutNulls))
        assertEquals(false, DFilter.ById(null, DFilter.ById.What.COLLECTION).existsIn(withoutNulls))
        assertEquals(false, DFilter.ById(null, DFilter.ById.What.FOLDER).existsIn(withoutNulls))
    }

    @Test
    fun `empty cipher list yields false for indexable and null for non-indexable`() = runTest {
        val empty = DFilterCipherPresence.of(emptyList<DSecret>()) { it }

        DFilter.ById.What.entries.forEach { what ->
            listOf("some-id", null).forEach { id ->
                assertFalse(
                    DFilter.ById(id = id, what = what).existsIn(empty) == true,
                    "ById($id, $what) must not match an empty list",
                )
            }
        }
        DSecret.Type.entries.forEach { type ->
            assertFalse(DFilter.ByType(type).existsIn(empty) == true)
        }
        PasswordStrength.Score.entries.forEach { score ->
            assertFalse(DFilter.ByPasswordStrength(score).existsIn(empty) == true)
        }
        listOf(true, false).forEach { value ->
            assertFalse(DFilter.BySync(value).existsIn(empty) == true)
            assertFalse(DFilter.ByReprompt(value).existsIn(empty) == true)
            assertFalse(DFilter.ByError(value).existsIn(empty) == true)
        }
        assertFalse(DFilter.ByFavorite.existsIn(empty) == true)
        assertFalse(DFilter.ByOtp.existsIn(empty) == true)
        assertFalse(DFilter.ByAttachments.existsIn(empty) == true)
        assertFalse(DFilter.ByPasskeys.existsIn(empty) == true)
        assertFalse(DFilter.ByIgnoredAlerts.existsIn(empty) == true)

        // Non-indexable primitives stay null regardless of the list.
        assertNull(DFilter.ByIncomplete.existsIn(empty))
        assertNull(DFilter.ByExpiring.existsIn(empty))
    }

    @Test
    fun `non-indexable primitives return null`() = runTest {
        assertNull(DFilter.ByPasswordValue(value = "x").existsIn(presence))
        assertNull(DFilter.ByWeakSshKeys.existsIn(presence))
        assertNull(DFilter.ByUnusableGpgKeys.existsIn(presence))
        assertNull(DFilter.ByWeakGpgKeys.existsIn(presence))
        assertNull(DFilter.ByGpgKeyPublishing.existsIn(presence))
        assertNull(DFilter.ByPasswordDuplicates.existsIn(presence))
        assertNull(DFilter.ByPasswordPwned.existsIn(presence))
        assertNull(DFilter.ByWebsitePwned.existsIn(presence))
        assertNull(DFilter.ByIncomplete.existsIn(presence))
        assertNull(DFilter.ByExpiring.existsIn(presence))
        assertNull(DFilter.ByUnsecureWebsites.existsIn(presence))
        assertNull(DFilter.ByTfaWebsites.existsIn(presence))
        assertNull(DFilter.ByPasskeyWebsites.existsIn(presence))
        assertNull(DFilter.ByDuplicateWebsites.existsIn(presence))
        assertNull(DFilter.ByBroadWebsites.existsIn(presence))
    }
}
