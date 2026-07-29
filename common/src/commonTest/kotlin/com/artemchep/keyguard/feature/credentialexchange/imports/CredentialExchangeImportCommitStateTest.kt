package com.artemchep.keyguard.feature.credentialexchange.imports

import com.artemchep.keyguard.common.service.credentialexchange.CxfImportPlan
import com.artemchep.keyguard.common.service.credentialexchange.cxfImportSkips
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialExchangeImportCommitStateTest {
    private val review = Step.Review(
        plan = CxfImportPlan(
            exporterRpId = "com.example.exporter",
            exporterDisplayName = "Exporter",
            sourceAccountCount = 1,
            folders = emptyList(),
            items = emptyList(),
            skips = cxfImportSkips(),
        ),
        sourcePackageName = "com.example.exporter",
        untitledLabel = UNTITLED,
    )

    @Test
    fun `two confirm callbacks can claim the review only once`() {
        val sink = MutableStateFlow<Step>(review)

        val importing = sink.claimCommit(review)

        assertNotNull(importing)
        assertTrue(importing.importing)
        assertEquals(importing, sink.value)
        assertNull(sink.claimCommit(review))
        assertEquals(importing, sink.value)
    }

    @Test
    fun `cancel followed by a stale confirm does not start a commit`() {
        val sink = MutableStateFlow<Step>(review)

        assertTrue(sink.cancelReview(review))
        assertEquals(Step.Start, sink.value)
        assertNull(sink.claimCommit(review))
        assertEquals(Step.Start, sink.value)
    }

    @Test
    fun `confirm followed by a stale cancel keeps the commit claimed`() {
        val sink = MutableStateFlow<Step>(review)
        val importing = assertNotNull(sink.claimCommit(review))

        assertFalse(sink.cancelReview(review))
        assertEquals(importing, sink.value)
    }

    @Test
    fun `selection callback changes only the current unclaimed review`() {
        val itemReview = Step.Review(
            plan = review.plan.copy(
                items = listOf(item("First"), item("Second")),
            ),
            sourcePackageName = review.sourcePackageName,
            untitledLabel = review.untitledLabel,
        )
        val sink = MutableStateFlow<Step>(itemReview)

        assertTrue(sink.setReviewItemSelected(itemReview, index = 0, selected = false))
        val changed = assertIs<Step.Review>(sink.value)
        assertEquals(setOf(1), changed.selectedItemIndexes)

        // The callback captured by the old row cannot overwrite the newer step.
        assertFalse(sink.setReviewItemSelected(itemReview, index = 1, selected = false))
        assertEquals(changed, sink.value)

        val importing = assertNotNull(sink.claimCommit(changed))
        assertFalse(sink.setReviewItemSelected(importing, index = 1, selected = false))
        assertEquals(importing, sink.value)
    }

    @Test
    fun `selection callback cannot change a cancelled review`() {
        val itemReview = Step.Review(
            plan = review.plan.copy(items = listOf(item("First"))),
            sourcePackageName = review.sourcePackageName,
            untitledLabel = review.untitledLabel,
        )
        val sink = MutableStateFlow<Step>(itemReview)

        assertTrue(sink.cancelReview(itemReview))
        assertFalse(sink.setReviewItemSelected(itemReview, index = 0, selected = false))
        assertEquals(Step.Start, sink.value)
    }

    private fun item(title: String) = CxfImportPlan.Item(
        request = com.artemchep.keyguard.common.model.create.CreateRequest(
            title = title,
            now = kotlin.time.Instant.parse("2024-01-30T14:09:33Z"),
        ),
        folderKey = null,
    )
}

private const val UNTITLED = "(none)"
