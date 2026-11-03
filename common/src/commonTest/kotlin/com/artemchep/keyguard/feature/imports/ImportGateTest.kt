package com.artemchep.keyguard.feature.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.CredentialExchangeImportTransportResult
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportFormatId
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.importSkips
import com.artemchep.keyguard.feature.credentialexchange.imports.debugPayloadOrNull
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
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
 * the other whether that review may be confirmed.
 *
 * Both read [isImportable], and these tests verify that the same plans pass both.
 */
class ImportGateTest {
    @Test
    fun `the start stage forwards the migration documentation action`() {
        var opened = false
        val stage = Step.Start.toTestStage(
            actions = importStageActions(
                onLearnMore = {
                    opened = true
                },
            ),
        )

        val start = assertIs<ImportState.Stage.Start>(stage)
        assertNotNull(start.onLearnMore).invoke()
        assertTrue(opened)
        assertEquals(
            "https://keyguard.dev/docs/migrating/",
            IMPORT_DOCS_URL,
        )
    }

    @Test
    fun `the start stage lists credential exchange only where it is offered`() {
        fun start(credentialExchange: Boolean) = assertIs<ImportState.Stage.Start>(
            Step.Start.toTestStage(
                credentialExchange = credentialExchange,
            ),
        )

        assertNotNull(start(credentialExchange = true).onCredentialExchange)
        assertNull(start(credentialExchange = false).onCredentialExchange)
    }

    @Test
    fun `the start stage lists every format and picks a file for the tapped one`() {
        val picked = mutableListOf<ImportFormatId>()
        val stage = Step.Start.toTestStage(
            actions = importStageActions(
                onPickFile = { format ->
                    picked += format.id
                },
            ),
            formats = formats,
        )

        val start = assertIs<ImportState.Stage.Start>(stage)
        assertEquals(
            listOf("Chrome", "Firefox"),
            start.formats.map { it.title },
        )
        assertEquals(
            listOf("Edge, Brave · CSV", "CSV"),
            start.formats.map { it.text },
        )
        assertNotNull(start.formats[1].onClick).invoke()
        assertEquals(listOf(ImportFormatId.Firefox), picked)
    }

    @Test
    fun `a file error offers to pick another file`() {
        val picked = mutableListOf<ImportFormatId>()
        val stage = Step.Error(
            message = "Not a Firefox export",
            retry = Retry.Pick(
                ImportSourceRef.File(
                    format = formats[1],
                    file = FilePickerResult(leParseUri("file:///tmp/export.csv"), "export.csv", null),
                ),
            ),
        ).toTestStage(
            actions = importStageActions(
                onPickFile = { format ->
                    picked += format.id
                },
            ),
            formats = formats,
        )

        val error = assertIs<ImportState.Stage.Error>(stage)
        assertNotNull(error.onRetry)
        assertEquals(emptyList(), picked)
    }

    @Test
    fun `only retryable errors offer the retry action`() {
        val retryable = Step.Error(
            message = "Try again",
            retry = Retry.Transfer,
        ).toTestStage()
        val nonRetryable = Step.Error(
            message = "Unavailable",
            retry = null,
        ).toTestStage()

        assertNotNull(
            assertIs<ImportState.Stage.Error>(retryable).onRetry,
        )
        assertNull(
            assertIs<ImportState.Stage.Error>(nonRetryable).onRetry,
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
        // transfer coroutine.
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

    private val formats = listOf(
        ImportFormat(
            id = ImportFormatId.Chromium,
            title = "Chrome",
            aliases = listOf("Edge", "Brave"),
            extensions = listOf("csv"),
        ),
        ImportFormat(
            id = ImportFormatId.Firefox,
            title = "Firefox",
            extensions = listOf("csv"),
        ),
    )

    private fun reviewStage(
        plan: ImportPlan,
        importing: Boolean = false,
        onSavePayload: (() -> Unit)? = null,
        selectedItemIndexes: Set<Int> = plan.items.indices.toSet(),
    ): ImportState.Stage.Review {
        val stage = Step.Review(
            plan = plan,
            sourcePackageName = "com.example.exporter",
            untitledLabel = "(none)",
            selectedItemIndexes = selectedItemIndexes,
            importing = importing,
        ).toTestStage(
            onSavePayload = onSavePayload,
        )
        return assertIs<ImportState.Stage.Review>(stage)
    }

    private fun plan(
        items: List<ImportPlan.Item> = emptyList(),
        folders: List<ImportPlan.Folder> = emptyList(),
    ) = ImportPlan(
        source = ImportPlan.Source(
            displayName = "Exporter",
            identifier = "com.example.exporter",
        ),
        sourceAccountCount = 1,
        folders = folders,
        items = items,
        skips = importSkips(),
    )

    private fun folder() = ImportPlan.Folder(
        key = "acc-0/0",
        parentKey = null,
        title = "Work",
    )

    private fun item() = ImportPlan.Item(
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
