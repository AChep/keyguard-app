package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.common.service.credentialexchange.cxfImportSkips
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Instant

class CxfImportSelectionTest {
    @Test
    fun `a new review selects every item and reports selected counts`() {
        val review = Step.Review(
            plan = plan(
                items = listOf(
                    item("Login", type = DSecret.Type.Login),
                    item("Card", type = DSecret.Type.Card),
                ),
            ),
            sourcePackageName = "com.example.exporter",
            untitledLabel = "(none)",
        )

        assertEquals(setOf(0, 1), review.selectedItemIndexes)
        assertEquals(1, review.counts.loginCount)
        assertEquals(1, review.counts.cardCount)
        val stage = review.toStage(
            onImport = {},
            onLearnMore = {},
            onConfirm = {},
            onCancel = {},
            onClose = {},
        ) as CredentialExchangeImportState.Stage.Review
        assertEquals(listOf(true, true), stage.items.map { it.selected })
    }

    @Test
    fun `a changed selection updates the review rows and summary counts`() {
        val review = Step.Review(
            plan = plan(
                items = listOf(
                    item("Login", type = DSecret.Type.Login),
                    item("Card", type = DSecret.Type.Card),
                ),
            ),
            sourcePackageName = "com.example.exporter",
            untitledLabel = "(none)",
            selectedItemIndexes = setOf(1),
        )

        assertEquals(0, review.counts.loginCount)
        assertEquals(1, review.counts.cardCount)
        val stage = review.toStage(
            onImport = {},
            onLearnMore = {},
            onConfirm = {},
            onCancel = {},
            onClose = {},
        ) as CredentialExchangeImportState.Stage.Review
        assertEquals(listOf("Card", "Login"), stage.items.map { it.item.title })
        assertEquals(listOf(true, false), stage.items.map { it.selected })
    }

    @Test
    fun `a sorted row changes the selection of its original plan item`() {
        val review = Step.Review(
            plan = plan(
                items = listOf(
                    item("Zulu", type = DSecret.Type.Login),
                    item("Alpha", type = DSecret.Type.Card),
                ),
            ),
            sourcePackageName = "com.example.exporter",
            untitledLabel = "(none)",
        )
        val sink = MutableStateFlow<Step>(review)
        val stage = review.toStage(
            onImport = {},
            onLearnMore = {},
            onConfirm = {},
            onCancel = {},
            onClose = {},
            onItemSelectionChange = { currentReview, index, selected ->
                sink.setReviewItemSelected(currentReview, index, selected)
            },
        ) as CredentialExchangeImportState.Stage.Review

        assertEquals("Alpha", stage.items.first().item.title)
        stage.items.first().onSelectedChange?.invoke(false)
        val changed = assertIs<Step.Review>(sink.value)
        assertEquals(setOf(0), changed.selectedItemIndexes)
        assertEquals(1, changed.counts.loginCount)
        assertEquals(0, changed.counts.cardCount)
    }

    @Test
    fun `a subset keeps item order and required folder ancestors`() {
        val root = folder(key = "root", parentKey = null)
        val child = folder(key = "child", parentKey = "root")
        val unused = folder(key = "unused", parentKey = null)
        val source = plan(
            folders = listOf(root, child, unused),
            items = listOf(
                item("First", folderKey = "unused"),
                item("Second", folderKey = "child"),
                item("Third"),
            ),
        )

        val selected = source.selectItems(setOf(1, 2))

        assertEquals(listOf("Second", "Third"), selected.items.map { it.request.title })
        assertEquals(listOf("root", "child"), selected.folders.map { it.key })
    }

    @Test
    fun `all selected returns the complete original plan including standalone folders`() {
        val source = plan(
            folders = listOf(folder(key = "standalone", parentKey = null)),
            items = listOf(item("First")),
        )

        assertSame(source, source.selectItems(setOf(0)))
    }

    @Test
    fun `restoring all selections restores the complete plan`() {
        val source = plan(
            folders = listOf(folder(key = "standalone", parentKey = null)),
            items = listOf(item("First"), item("Second")),
        )

        val subset = source.selectItems(setOf(1))
        val restored = source.selectItems(setOf(0, 1))

        assertEquals(emptyList(), subset.folders)
        assertSame(source, restored)
    }

    @Test
    fun `deselecting all items produces an empty plan but folders-only stays intact`() {
        val withItems = plan(
            folders = listOf(folder(key = "folder", parentKey = null)),
            items = listOf(item("First", folderKey = "folder")),
        )
        val foldersOnly = plan(
            folders = listOf(folder(key = "folder", parentKey = null)),
        )

        assertEquals(emptyList(), withItems.selectItems(emptySet()).items)
        assertEquals(emptyList(), withItems.selectItems(emptySet()).folders)
        assertSame(foldersOnly, foldersOnly.selectItems(emptySet()))
    }

    private fun plan(
        folders: List<CxfImportPlan.Folder> = emptyList(),
        items: List<CxfImportPlan.Item> = emptyList(),
    ) = CxfImportPlan(
        exporterRpId = "com.example.exporter",
        exporterDisplayName = "Exporter",
        sourceAccountCount = 1,
        folders = folders,
        items = items,
        skips = cxfImportSkips(),
    )

    private fun folder(
        key: String,
        parentKey: String?,
    ) = CxfImportPlan.Folder(
        key = key,
        parentKey = parentKey,
        title = key,
    )

    private fun item(
        title: String,
        type: DSecret.Type = DSecret.Type.Login,
        folderKey: String? = null,
    ) = CxfImportPlan.Item(
        request = CreateRequest(
            title = title,
            type = type,
            now = NOW,
        ),
        folderKey = folderKey,
    )
}

private val NOW = Instant.parse("2024-01-30T14:09:33Z")
