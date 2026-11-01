package com.artemchep.keyguard.feature.imports

/**
 * Claims ownership of [review] for one commit. A second Confirm against the
 * importing step, or a Confirm immediately after Cancel, cannot claim it.
 *
 * The commit takes over what the review held, so that leaving the importing
 * step, e.g. as the screen closes, does not close the source it reads.
 */
internal fun ImportSteps.claimCommit(
    review: Step.Review,
): ImportCommitClaim? {
    if (review.importing) {
        return null
    }
    val importingReview = review.copy(
        importing = true,
        held = emptySet(),
    )
    return claim(review, importingReview)?.let { lease ->
        ImportCommitClaim(
            review = importingReview,
            lease = lease,
        )
    }
}

/**
 * Cancels only while the unclaimed [review] remains current. In particular, a
 * stale Cancel callback cannot roll an already claimed commit back to Start.
 */
internal fun ImportSteps.cancelReview(
    review: Step.Review,
): Boolean = !review.importing && move(review, Step.Start)

/**
 * Changes one selection only while [review] is still the current, unclaimed step.
 * Callbacks held by disposed lazy rows therefore cannot alter a later stage.
 */
internal fun ImportSteps.setReviewItemSelected(
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
        move(
            review,
            review.copy(selectedItemIndexes = selectedItemIndexes),
        )
    }
}
