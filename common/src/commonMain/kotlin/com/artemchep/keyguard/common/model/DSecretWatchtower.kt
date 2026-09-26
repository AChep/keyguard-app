package com.artemchep.keyguard.common.model

/** Archived and trashed items do not participate in Watchtower checks. */
val DSecret.isWatchtowerEligible: Boolean
    get() = !deleted && !archived
