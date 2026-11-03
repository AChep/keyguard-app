package com.artemchep.keyguard.common.service.icon.coil

import android.content.pm.PackageManager
import coil3.fetch.FetchResult
import coil3.request.Options
import com.artemchep.keyguard.android.coil3.toAppIconImage

internal actual suspend fun installedAppIconOrNull(
    packageName: String,
    options: Options,
): FetchResult? {
    if (packageName.isEmpty()) {
        return null
    }
    return try {
        options.context.packageManager
            .getApplicationIcon(packageName)
            .toAppIconImage(options)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}
