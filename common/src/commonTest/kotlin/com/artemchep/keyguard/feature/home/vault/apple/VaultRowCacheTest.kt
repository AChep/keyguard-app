package com.artemchep.keyguard.feature.home.vault.apple

import androidx.compose.ui.text.AnnotatedString
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.KeePassIcon
import com.artemchep.keyguard.feature.attachments.SelectableItemState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.test.createSecret
import com.artemchep.keyguard.test.testCopyText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant

class VaultRowCacheTest {
    private val copyText = testCopyText()

    private fun secret(
        id: String,
        name: String = "Item $id",
    ) = createSecret(
        id = id,
        name = name,
        accountId = "account",
        login = DSecret.Login(
            username = "user@example.com",
        ),
        revisionDate = Instant.fromEpochMilliseconds(1_000_000L),
        createdDate = null,
    )

    private fun dummyItem(secret: DSecret) = VaultItem2.Item(
        id = secret.id,
        source = secret,
        accentLight = secret.accentLight,
        accentDark = secret.accentDark,
        accountId = secret.accountId,
        groupId = null,
        revisionDate = secret.revisionDate,
        createdDate = secret.createdDate,
        password = null,
        passwordRevisionDate = null,
        score = null,
        type = secret.type.name,
        folderId = secret.folderId,
        icon = VaultItemIcon.TextIcon(text = "IT"),
        feature = VaultItem2.Item.Feature.None,
        copyText = copyText,
        token = null,
        passwords = persistentListOf(),
        passkeys = persistentListOf(),
        attachments2 = persistentListOf(),
        title = AnnotatedString(secret.name),
        text = null,
        favourite = secret.favorite,
        attachments = false,
        action = VaultItem2.Item.Action.None,
        localStateSource = VaultItem2.Item.LocalStateSource.PerItem(
            MutableStateFlow(
                VaultItem2.Item.LocalState(
                    openedState = VaultItem2.Item.OpenedState(isOpened = false),
                    selectableItemState = SelectableItemState(
                        selecting = false,
                        selected = false,
                        onClick = null,
                        onLongClick = null,
                    ),
                ),
            ),
        ),
    )

    private inner class Fixture(
        val logs: MutableList<String> = mutableListOf(),
    ) {
        var rebuildCount = 0
            private set

        val cache = VaultRowCache(
            rebuild = { secret ->
                rebuildCount += 1
                CachedRow(
                    fingerprint = 0L,
                    item = dummyItem(secret),
                )
            },
            log = { message -> logs += message },
        )
    }

    @Test
    fun `unchanged inputs rebuild nothing and reuse row instances`() {
        val fixture = Fixture()
        val secrets = (0 until 10).map { secret("id$it") }
        val epochs = FingerprintEpochs()

        val first = fixture.cache.reconcile(secrets, epochs)
        assertEquals(10, first.rebuiltCount)
        assertEquals(10, fixture.rebuildCount)

        val second = fixture.cache.reconcile(secrets, epochs)
        assertEquals(0, second.rebuiltCount)
        assertTrue(second.changedIds.isEmpty())
        assertEquals(10, fixture.rebuildCount)
        for (i in secrets.indices) {
            assertSame(
                first.rows[i], second.rows[i],
                "an unchanged row must be the exact cached instance",
            )
        }
    }

    @Test
    fun `a single field change rebuilds exactly that row`() {
        val fixture = Fixture()
        val secrets = (0 until 10).map { secret("id$it") }
        val epochs = FingerprintEpochs()
        fixture.cache.reconcile(secrets, epochs)

        val mutated = secrets.toMutableList()
        mutated[3] = mutated[3].copy(name = "Renamed")
        val result = fixture.cache.reconcile(mutated, epochs)
        assertEquals(1, result.rebuiltCount)
        assertEquals(setOf("id3"), result.changedIds)
        assertEquals(11, fixture.rebuildCount)
    }

    @Test
    fun `an epoch bump rebuilds every row`() {
        val fixture = Fixture()
        val secrets = (0 until 10).map { secret("id$it") }
        val epochs = FingerprintEpochs()
        fixture.cache.reconcile(secrets, epochs)

        for (bumped in listOf(
            epochs.copy(config = 1L),
            epochs.copy(org = 1L),
            epochs.copy(locale = 1L),
        )) {
            val freshFixture = Fixture()
            freshFixture.cache.reconcile(secrets, epochs)
            val result = freshFixture.cache.reconcile(secrets, bumped)
            assertEquals(10, result.rebuiltCount, "epoch $bumped must rebuild all rows")
            assertEquals(secrets.map { it.id }.toSet(), result.changedIds)
        }
    }

    @Test
    fun `evicted ids are rebuilt when they come back`() {
        val fixture = Fixture()
        val secrets = (0 until 6).map { secret("id$it") }
        val epochs = FingerprintEpochs()
        fixture.cache.reconcile(secrets, epochs)

        val subset = secrets.filter { it.id != "id4" }
        val dropped = fixture.cache.reconcile(subset, epochs)
        assertEquals(0, dropped.rebuiltCount)

        val restored = fixture.cache.reconcile(secrets, epochs)
        assertEquals(setOf("id4"), restored.changedIds)
    }

    @Test
    fun `fingerprints are stable across runs and structurally equal copies`() {
        val epochs = FingerprintEpochs(config = 7L, org = 3L, locale = 1L)
        val a = secret("id0")
        assertEquals(fingerprintOf(a, epochs), fingerprintOf(a, epochs))
        assertEquals(
            fingerprintOf(a, epochs),
            fingerprintOf(a.copy(), epochs),
            "a structurally equal copy must fingerprint identically",
        )
        assertNotEquals(
            fingerprintOf(a, epochs),
            fingerprintOf(secret("id1"), epochs),
        )
        assertNotEquals(
            fingerprintOf(a, epochs),
            fingerprintOf(a, epochs.copy(config = 8L)),
        )
    }

    @Test
    fun `fingerprint input names are unique`() {
        val names = vaultRowFingerprintInputs.map { it.first }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `every documented input field changes the fingerprint`() {
        val epochs = FingerprintEpochs()
        val base = secret("id0")
        val baseFingerprint = fingerprintOf(base, epochs)
        val mutations: List<Pair<String, DSecret>> = listOf(
            "name" to base.copy(name = "Changed"),
            "favorite" to base.copy(favorite = true),
            "reprompt" to base.copy(reprompt = true),
            "synced" to base.copy(synced = false),
            "notes" to base.copy(notes = "A note"),
            "revisionDate" to base.copy(revisionDate = Instant.fromEpochMilliseconds(2_000_000L)),
            "createdDate" to base.copy(createdDate = Instant.fromEpochMilliseconds(500_000L)),
            "deletedDate" to base.copy(deletedDate = Instant.fromEpochMilliseconds(3_000_000L)),
            "archivedDate" to base.copy(archivedDate = Instant.fromEpochMilliseconds(3_000_000L)),
            "folderId" to base.copy(folderId = "folder"),
            "organizationId" to base.copy(organizationId = "org"),
            "collectionIds" to base.copy(collectionIds = setOf("collection")),
            "tags" to base.copy(tags = listOf("tag")),
            "uris" to base.copy(uris = listOf(DSecret.Uri(uri = "https://example.com"))),
            "customIcon" to base.copy(customIcon = KeePassIcon.World),
            "type" to base.copy(type = DSecret.Type.SecureNote),
            "attachments" to base.copy(
                attachments = listOf(
                    DSecret.Attachment.Local(
                        id = "attachment",
                        url = "file://attachment",
                        fileName = "file.txt",
                        size = 128L,
                    ),
                ),
            ),
            "login.username" to base.copy(login = base.login?.copy(username = "other@example.com")),
            "login.password" to base.copy(login = base.login?.copy(password = "hunter2")),
            "login.passwordRevisionDate" to base.copy(
                login = base.login?.copy(
                    passwordRevisionDate = Instant.fromEpochMilliseconds(4_000_000L),
                ),
            ),
            "card.brand" to base.copy(card = DSecret.Card(brand = "Visa")),
            "card.number" to base.copy(card = DSecret.Card(number = "4111111111111111")),
            "identity.firstName" to base.copy(identity = DSecret.Identity(firstName = "John")),
            "sshKey.fingerprint" to base.copy(sshKey = DSecret.SshKey(fingerprint = "SHA256:abc")),
            "gpgKey.fingerprint" to base.copy(gpgKey = DSecret.GpgKey(fingerprint = "ABCD1234")),
        )
        for ((label, mutated) in mutations) {
            assertNotEquals(
                baseFingerprint,
                fingerprintOf(mutated, epochs),
                "mutating '$label' must change the fingerprint",
            )
        }
    }

    @Test
    fun `mass rebuild with unchanged epochs logs the instability warning`() {
        val fixture = Fixture()
        val secrets = (0 until 16).map { secret("id$it") }
        val epochs = FingerprintEpochs()

        // The initial fill is all-new rows, not rebuilt-existing ones:
        // it must stay silent.
        fixture.cache.reconcile(secrets, epochs)
        fixture.cache.reconcile(secrets, epochs)
        assertTrue(fixture.logs.isEmpty(), "steady state must not warn")

        // An epoch bump legitimately rebuilds everything: still silent.
        fixture.cache.reconcile(secrets, epochs.copy(config = 1L))
        assertTrue(fixture.logs.isEmpty(), "an epoch bump must not warn")

        // Every row changing under unchanged epochs is the instability
        // signature the guard exists for.
        val mutated = secrets.map {
            it.copy(revisionDate = Instant.fromEpochMilliseconds(9_000_000L))
        }
        fixture.cache.reconcile(mutated, epochs.copy(config = 1L))
        assertEquals(1, fixture.logs.size)
        assertTrue(fixture.logs.single().contains("VaultRowCache"))
    }
}
