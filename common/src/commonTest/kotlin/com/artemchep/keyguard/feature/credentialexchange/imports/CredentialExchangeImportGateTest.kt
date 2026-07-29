package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransportResult
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.common.service.credentialexchange.cxfImportSkips
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The import flow has two gates: one decides whether a parsed plan is worth a review,
 * the other whether that review may be confirmed. They disagreed — the first admitted a
 * plan with folders and no items, the second required an item — so a folders-only plan
 * reached a review that listed the folders and offered no Import button at all, leaving
 * Cancel as the only action and dropping what the screen had just promised.
 *
 * Both now read [isImportable], and these pin that the same plans pass both.
 */
class CredentialExchangeImportGateTest {
    @Test
    fun `the start stage forwards the credential exchange documentation action`() {
        var opened = false
        val stage = Step.Start.toStage(
            onImport = {},
            onLearnMore = {
                opened = true
            },
            onConfirm = {},
            onCancel = {},
            onClose = {},
        )

        val start = assertIs<CredentialExchangeImportState.Stage.Start>(stage)
        assertNotNull(start.onLearnMore).invoke()
        assertTrue(opened)
        assertEquals(
            "https://keyguard.dev/docs/credential-exchange/#importing-from-another-app",
            CREDENTIAL_EXCHANGE_IMPORT_DOCS_URL,
        )
    }

    @Test
    fun `only retryable errors offer the retry action`() {
        val retryable = Step.Error(
            message = "Try again",
            retryable = true,
        ).toStage(
            onImport = {},
            onLearnMore = {},
            onConfirm = {},
            onCancel = {},
            onClose = {},
        )
        val nonRetryable = Step.Error(
            message = "Unavailable",
            retryable = false,
        ).toStage(
            onImport = {},
            onLearnMore = {},
            onConfirm = {},
            onCancel = {},
            onClose = {},
        )

        assertNotNull(
            assertIs<CredentialExchangeImportState.Stage.Error>(retryable).onRetry,
        )
        assertNull(
            assertIs<CredentialExchangeImportState.Stage.Error>(nonRetryable).onRetry,
        )
    }

    @Test
    fun `a folders-only plan is importable and offers the import`() {
        // Reachable whenever every received item is of an unsupported credential kind:
        // the folder plan prunes no item-less folder, so the tree survives on its own.
        val foldersOnly = plan(folders = listOf(folder()))
        assertTrue(foldersOnly.isImportable)
        val review = reviewStage(foldersOnly)
        // What the screen promises, and what it must therefore be able to deliver.
        assertEquals(1, review.folderCount)
        assertNotNull(review.onConfirm)
    }

    @Test
    fun `an items-only plan offers the import`() {
        val itemsOnly = plan(items = listOf(item()))
        assertTrue(itemsOnly.isImportable)
        assertNotNull(reviewStage(itemsOnly).onConfirm)
    }

    @Test
    fun `deselecting every available item withdraws the import`() {
        val review = reviewStage(
            plan = plan(
                items = listOf(item()),
                folders = listOf(folder()),
            ),
            selectedItemIndexes = emptySet(),
        )

        assertNull(review.onConfirm)
        assertEquals(0, review.folderCount)
        assertFalse(review.items.single().selected)
    }

    @Test
    fun `a plan with neither items nor folders is not importable`() {
        // The step this feeds is the empty stage, which is why such a plan never has to
        // answer for a missing Import button.
        assertFalse(plan().isImportable)
    }

    @Test
    fun `a claimed commit withdraws both actions`() {
        // The gates only ever *add* the confirm; an already importing review keeps
        // offering neither it nor the cancel.
        val review = reviewStage(plan(folders = listOf(folder())), importing = true)
        assertNull(review.onConfirm)
        assertNull(review.onCancel)
        assertTrue(review.isImporting)
    }

    @Test
    fun `the payload dump is offered only when a payload is being held`() {
        // Absence of the callback is the entire gate — the screen renders the row when
        // it is non-null, so a release build must arrive here with nothing to forward.
        assertNull(reviewStage(plan(items = listOf(item()))).onSaveDebugPayload)
        assertNotNull(
            reviewStage(plan(items = listOf(item())), onSavePayload = {})
                .onSaveDebugPayload,
        )
    }

    @Test
    fun `a claimed commit withdraws the payload dump too`() {
        // The payload is already on its way into the vault; the review is over.
        val review = reviewStage(
            plan = plan(items = listOf(item())),
            importing = true,
            onSavePayload = {},
        )
        assertNull(review.onSaveDebugPayload)
    }

    @Test
    fun `a received payload is retained for the dump only while enabled`() {
        val success = CredentialExchangeImportTransportResult.Success(
            payload = PAYLOAD,
            sourcePackageName = "com.example.exporter",
        )
        assertEquals(PAYLOAD, success.debugPayloadOrNull(enabled = true))
        // The release-build guarantee, and the reason the flag is a parameter rather
        // than a bare `isRelease` read: nothing retains the vault, so it dies with the
        // transfer coroutine exactly as it did before the dump existed.
        assertNull(success.debugPayloadOrNull(enabled = false))
    }

    @Test
    fun `a transfer that produced no payload retains nothing`() {
        // Also what clears the previous attempt: the sink is assigned this on every
        // outcome, so a cancelled retry cannot leave the last vault behind it.
        assertNull(
            CredentialExchangeImportTransportResult.Cancelled
                .debugPayloadOrNull(enabled = true),
        )
        CredentialExchangeImportTransportResult.Failure.Kind.entries.forEach { kind ->
            val failure = CredentialExchangeImportTransportResult.Failure(kind)
            assertNull(failure.debugPayloadOrNull(enabled = true), "kind=$kind")
        }
    }

    private fun reviewStage(
        plan: CxfImportPlan,
        importing: Boolean = false,
        onSavePayload: (() -> Unit)? = null,
        selectedItemIndexes: Set<Int> = plan.items.indices.toSet(),
    ): CredentialExchangeImportState.Stage.Review {
        val stage = Step.Review(
            plan = plan,
            sourcePackageName = "com.example.exporter",
            untitledLabel = "(none)",
            selectedItemIndexes = selectedItemIndexes,
            importing = importing,
        ).toStage(
            onImport = {},
            onLearnMore = {},
            onConfirm = {},
            onCancel = {},
            onClose = {},
            onSavePayload = onSavePayload,
        )
        return assertIs<CredentialExchangeImportState.Stage.Review>(stage)
    }

    private fun plan(
        items: List<CxfImportPlan.Item> = emptyList(),
        folders: List<CxfImportPlan.Folder> = emptyList(),
    ) = CxfImportPlan(
        exporterRpId = "com.example.exporter",
        exporterDisplayName = "Exporter",
        sourceAccountCount = 1,
        folders = folders,
        items = items,
        skips = cxfImportSkips(),
    )

    private fun folder() = CxfImportPlan.Folder(
        key = "acc-0/0",
        parentKey = null,
        title = "Work",
    )

    private fun item() = CxfImportPlan.Item(
        request = CreateRequest(
            title = "Netflix",
            type = DSecret.Type.Login,
            now = NOW,
        ),
        folderKey = null,
    )
}

private val NOW = Instant.parse("2024-01-30T14:09:33Z")

private const val PAYLOAD = """{"version":{"major":1,"minor":0},"accounts":[]}"""
