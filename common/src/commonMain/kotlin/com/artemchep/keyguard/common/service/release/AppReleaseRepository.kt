package com.artemchep.keyguard.common.service.release

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AppRelease

/**
 * Lists the public releases of the app.
 */
interface AppReleaseRepository {
    /** Fetches the recent releases, skipping the drafts and the prereleases. */
    fun getReleases(): IO<List<AppRelease>>
}
