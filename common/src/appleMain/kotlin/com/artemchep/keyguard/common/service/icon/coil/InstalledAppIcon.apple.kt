package com.artemchep.keyguard.common.service.icon.coil

import coil3.fetch.FetchResult
import coil3.request.Options

internal actual suspend fun installedAppIconOrNull(
    packageName: String,
    options: Options,
): FetchResult? = null
