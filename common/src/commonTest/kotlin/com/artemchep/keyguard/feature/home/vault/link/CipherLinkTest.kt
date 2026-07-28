package com.artemchep.keyguard.feature.home.vault.link

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.service.cipherlink.CipherLink
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
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
            links = listOf(
                DSecret.Link(TARGET_REMOTE_ID),
                DSecret.Link(MISSING_REMOTE_ID),
            ),
            fields = listOf(
                // A custom field that merely holds a link uri is not a
                // link, only the reserved fields are.
                DSecret.Field(
                    name = "Ordinary link",
                    value = requireNotNull(CipherLink.of(TARGET_REMOTE_ID)).toString(),
                    type = DSecret.Field.Type.Text,
                ),
            ),
        )

        val relations = resolveCipherRelations(current, listOf(current, target))

        assertEquals(2, relations.outgoingTargets.size)
        assertSame(target, relations.outgoingTargets[0])
        assertNull(relations.outgoingTargets[1])
    }

    @Test
    fun `collapses duplicate links to the same target`() {
        val target = cipher(
            localId = "target-local",
            remoteId = TARGET_REMOTE_ID,
        )
        val current = cipher(
            localId = "current-local",
            remoteId = CURRENT_REMOTE_ID,
            links = listOf(
                DSecret.Link(TARGET_REMOTE_ID),
                DSecret.Link(TARGET_REMOTE_ID),
            ),
        )

        val relations = resolveCipherRelations(current, listOf(current, target))

        assertEquals(1, relations.outgoingTargets.size)
        assertSame(target, relations.outgoingTargets.single())
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
            links = listOf(DSecret.Link(CURRENT_REMOTE_ID)),
        )
        val crossAccountSource = cipher(
            localId = "cross-account-local",
            remoteId = CROSS_ACCOUNT_REMOTE_ID,
            accountId = "other-account",
            links = listOf(DSecret.Link(CURRENT_REMOTE_ID)),
        )
        val deletedSource = cipher(
            localId = "deleted-local",
            remoteId = DELETED_REMOTE_ID,
            links = listOf(DSecret.Link(CURRENT_REMOTE_ID)),
            deletedDate = NOW,
        )

        val relations = resolveCipherRelations(
            current,
            listOf(current, source, crossAccountSource, deletedSource),
        )

        assertEquals(1, relations.incomingSources.size)
        assertSame(source, relations.incomingSources.single())
    }

    @Test
    fun `lists a source that links here more than once only once`() {
        val current = cipher(
            localId = "current-local",
            remoteId = CURRENT_REMOTE_ID,
        )
        val source = cipher(
            localId = "source-local",
            remoteId = SOURCE_REMOTE_ID,
            links = listOf(
                DSecret.Link(CURRENT_REMOTE_ID),
                DSecret.Link(CURRENT_REMOTE_ID),
            ),
        )

        val relations = resolveCipherRelations(current, listOf(current, source))

        assertEquals(listOf(source), relations.incomingSources)
    }

    @Test
    fun `treats a self link as unavailable`() {
        val current = cipher(
            localId = "current-local",
            remoteId = CURRENT_REMOTE_ID,
            links = listOf(DSecret.Link(CURRENT_REMOTE_ID)),
        )

        val relations = resolveCipherRelations(current, listOf(current))

        assertEquals(1, relations.outgoingTargets.size)
        assertNull(relations.outgoingTargets.single())
        assertEquals(emptyList(), relations.incomingSources)
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

        val targets = cipherLinkTargetsByRemoteId(
            ciphers = listOf(selectable, excluded, crossAccount, deleted, localOnly),
            accountId = "account",
            excludedCipherId = excluded.id,
        )
        val result = filterCipherLinkPickerTargets(
            targets = targets.values,
            query = "gmail",
        ).map(CipherLinkTarget::cipher)

        assertEquals(listOf(selectable), result)
    }

    @Test
    fun `target index keeps the last cipher for a duplicate canonical remote id`() {
        val first = cipher(
            localId = "first",
            remoteId = TARGET_REMOTE_ID.uppercase(),
        )
        val last = cipher(
            localId = "last",
            remoteId = TARGET_REMOTE_ID,
        )

        val targets = cipherLinkTargetsByRemoteId(
            ciphers = listOf(first, last),
            accountId = "account",
        )

        assertSame(last, targets.getValue(TARGET_REMOTE_ID).cipher)
    }

    @Test
    fun `picker query changes reuse the shared candidate index`() = runTest {
        var subscriptions = 0
        val selectable = cipher(
            localId = "selectable",
            remoteId = TARGET_REMOTE_ID,
            name = "Google account",
            username = "user@gmail.com",
        )
        val targetsFlow = createCipherLinkPickerTargetsFlow(
            ciphersFlow = flow {
                subscriptions += 1
                emit(listOf(selectable))
                awaitCancellation()
            },
            accountId = "account",
            excludedCipherId = null,
            sharingScope = backgroundScope,
        )
        val queryFlow = MutableStateFlow("")
        val results = mutableListOf<List<CipherLinkTarget>>()
        val collector = backgroundScope.launch(
            UnconfinedTestDispatcher(testScheduler),
        ) {
            combine(targetsFlow, queryFlow, ::filterCipherLinkPickerTargets)
                .take(3)
                .toList(results)
        }
        testScheduler.runCurrent()

        queryFlow.value = "google"
        testScheduler.runCurrent()
        queryFlow.value = "gmail"
        testScheduler.runCurrent()
        collector.join()

        assertEquals(1, subscriptions)
        assertEquals(3, results.size)
        assertEquals(
            listOf(selectable),
            results.last().map(CipherLinkTarget::cipher),
        )
    }

    private fun cipher(
        localId: String,
        remoteId: String?,
        name: String = localId,
        accountId: String = "account",
        fields: List<DSecret.Field> = emptyList(),
        links: List<DSecret.Link> = emptyList(),
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
        links = links,
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
