package com.artemchep.keyguard.feature.credentialexchange.imports

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Claims ownership of [review] for one commit. A second Confirm against the
 * importing step, or a Confirm immediately after Cancel, cannot claim it.
 */
internal fun MutableStateFlow<Step>.claimCommit(
    review: Step.Review,
): Step.Review? = if (review.importing) {
    null
} else {
    val importingReview = review.copy(importing = true)
    importingReview.takeIf {
        compareAndSet(review, importingReview)
    }
}

/**
 * Cancels only while the unclaimed [review] remains current. In particular, a
 * stale Cancel callback cannot roll an already claimed commit back to Start.
 */
internal fun MutableStateFlow<Step>.cancelReview(
    review: Step.Review,
): Boolean = !review.importing && compareAndSet(review, Step.Start)

/**
 * Changes one selection only while [review] is still the current, unclaimed step.
 * Callbacks held by disposed lazy rows therefore cannot alter a later stage.
 */
internal fun MutableStateFlow<Step>.setReviewItemSelected(
    review: Step.Review,
    index: Int,
    selected: Boolean,
): Boolean {
    val canUpdate = !review.importing &&
        index in review.plan.items.indices &&
        value == review
    if (!canUpdate) {
        return false
    }

    val currentlySelected = index in review.selectedItemIndexes
    return if (currentlySelected == selected) {
        true
    } else {
        val selectedItemIndexes = if (selected) {
            review.selectedItemIndexes + index
        } else {
            review.selectedItemIndexes - index
        }
        compareAndSet(
            review,
            review.copy(selectedItemIndexes = selectedItemIndexes),
        )
    }
}
