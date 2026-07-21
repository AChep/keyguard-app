package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class CipherLinkTest {
    @Test
    fun `formats a canonical cipher link`() {
        val link = requireNotNull(
            CipherLink.of("A0Eebc99-9C0B-4EF8-BB6D-6BB9BD380A11"),
        )

        assertEquals(
            "keyguard://cipher/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
            link.toString(),
        )
    }

    @Test
    fun `parses a link with surrounding whitespace`() {
        val link = CipherLink.parse(
            "  keyguard://cipher/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11\n",
        )

        assertEquals(
            "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
            link?.remoteCipherId,
        )
    }

    @Test
    fun `rejects values outside the exact protocol`() {
        assertNull(CipherLink.parse(null))
        assertNull(CipherLink.parse("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"))
        assertNull(CipherLink.parse("https://cipher/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"))
        assertNull(CipherLink.parse("keyguard://item/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"))
        assertNull(CipherLink.parse("keyguard://cipher/not-a-uuid"))
        assertNull(CipherLink.parse("keyguard://cipher/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11/extra"))
        assertNull(CipherLink.parse("keyguard://cipher/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11?x=1"))
        assertNull(CipherLink.parse("keyguard://cipher/a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11#x"))
    }

    @Test
    fun `resolves outgoing links and keeps missing targets`() {
        val target = cipher(
            localId = "target-local",
            remoteId = TARGET_REMOTE_ID,
            name = "Google account",
        )
        val current = cipher(
            localId = "current-local",
            remoteId = CURRENT_REMOTE_ID,
            fields = listOf(
                textField("Sign in with Google", TARGET_REMOTE_ID),
                textField("Missing item", MISSING_REMOTE_ID),
                DSecret.Field(
                    name = "Hidden link",
                    value = requireNotNull(CipherLink.of(TARGET_REMOTE_ID)).toString(),
                    type = DSecret.Field.Type.Hidden,
                ),
                DSecret.Field(
                    name = "Ordinary UUID",
                    value = TARGET_REMOTE_ID,
                    type = DSecret.Field.Type.Text,
                ),
            ),
        )

        val relations = resolveCipherRelations(current, listOf(current, target))

        assertEquals(2, relations.outgoing.size)
        assertSame(target, relations.outgoing[0].cipher)
        assertNull(relations.outgoing[1].cipher)
        assertEquals(listOf(0, 1), relations.outgoing.map { it.fieldIndex })
    }

    @Test
    fun `derives backlinks only inside the same account`() {
        val current = cipher(
            localId = "current-local",
            remoteId = CURRENT_REMOTE_ID,
        )
        val source = cipher(
            localId = "source-local",
            remoteId = SOURCE_REMOTE_ID,
            fields = listOf(textField("Uses account", CURRENT_REMOTE_ID)),
        )
        val crossAccountSource = cipher(
            localId = "cross-account-local",
            remoteId = CROSS_ACCOUNT_REMOTE_ID,
            accountId = "other-account",
            fields = listOf(textField("Cross-account", CURRENT_REMOTE_ID)),
        )
        val deletedSource = cipher(
            localId = "deleted-local",
            remoteId = DELETED_REMOTE_ID,
            fields = listOf(textField("Deleted", CURRENT_REMOTE_ID)),
            deletedDate = NOW,
        )

        val relations = resolveCipherRelations(
            current,
            listOf(current, source, crossAccountSource, deletedSource),
        )

        assertEquals(1, relations.incoming.size)
        assertSame(source, relations.incoming.single().cipher)
        assertEquals("Uses account", relations.incoming.single().label)
    }

    @Test
    fun `treats a self link as unavailable`() {
        val current = cipher(
            localId = "current-local",
            remoteId = CURRENT_REMOTE_ID,
            fields = listOf(textField("Self", CURRENT_REMOTE_ID)),
        )

        val relations = resolveCipherRelations(current, listOf(current))

        assertEquals(1, relations.outgoing.size)
        assertNull(relations.outgoing.single().cipher)
        assertEquals(emptyList(), relations.incoming)
    }

    @Test
    fun `picker filters by account lifecycle and remote id`() {
        val selectable = cipher(
            localId = "selectable",
            remoteId = TARGET_REMOTE_ID,
            name = "Google account",
            username = "user@gmail.com",
        )
        val excluded = cipher(
            localId = "excluded",
            remoteId = SOURCE_REMOTE_ID,
        )
        val crossAccount = cipher(
            localId = "cross-account",
            remoteId = CROSS_ACCOUNT_REMOTE_ID,
            accountId = "other-account",
        )
        val deleted = cipher(
            localId = "deleted",
            remoteId = DELETED_REMOTE_ID,
            deletedDate = NOW,
        )
        val localOnly = cipher(
            localId = "local-only",
            remoteId = null,
        )

        val result = filterCipherLinkPickerCiphers(
            ciphers = listOf(selectable, excluded, crossAccount, deleted, localOnly),
            accountId = "account",
            excludedCipherId = excluded.id,
            query = "gmail",
        )

        assertEquals(listOf(selectable), result)
    }

    private fun textField(name: String, remoteId: String) = DSecret.Field(
        name = name,
        value = requireNotNull(CipherLink.of(remoteId)).toString(),
        type = DSecret.Field.Type.Text,
    )

    private fun cipher(
        localId: String,
        remoteId: String?,
        name: String = localId,
        accountId: String = "account",
        fields: List<DSecret.Field> = emptyList(),
        deletedDate: Instant? = null,
        username: String? = null,
    ) = DSecret(
        id = localId,
        accountId = accountId,
        folderId = null,
        organizationId = null,
        collectionIds = emptySet(),
        revisionDate = NOW,
        createdDate = NOW,
        archivedDate = null,
        deletedDate = deletedDate,
        service = BitwardenService(
            remote = remoteId?.let { id ->
                BitwardenService.Remote(
                    id = id,
                    revisionDate = NOW,
                    deletedDate = deletedDate,
                )
            },
        ),
        name = name,
        notes = "",
        favorite = false,
        reprompt = false,
        synced = true,
        fields = fields,
        type = DSecret.Type.Login,
        login = DSecret.Login(
            username = username,
        ),
    )

    private companion object {
        val NOW = Instant.fromEpochSeconds(1)
        const val CURRENT_REMOTE_ID = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"
        const val TARGET_REMOTE_ID = "b0eebc99-9c0b-4ef8-bb6d-6bb9bd380a12"
        const val MISSING_REMOTE_ID = "c0eebc99-9c0b-4ef8-bb6d-6bb9bd380a13"
        const val SOURCE_REMOTE_ID = "d0eebc99-9c0b-4ef8-bb6d-6bb9bd380a14"
        const val CROSS_ACCOUNT_REMOTE_ID = "e0eebc99-9c0b-4ef8-bb6d-6bb9bd380a15"
        const val DELETED_REMOTE_ID = "f0eebc99-9c0b-4ef8-bb6d-6bb9bd380a16"
    }
}
