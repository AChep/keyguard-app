package com.artemchep.keyguard.android.ipc

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import com.artemchep.keyguard.android.util.getAndroidPackageSigningCertificates
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.util.foundation.crypto.sha256

internal data class AndroidIpcCaller(
    val uid: Int,
    val pid: Int,
    val packageName: String,
    val appLabel: String,
    val certificateDigests: List<String>,
) {
    val principal: String = buildString {
        append(packageName)
        append(':')
        append(certificateDigests.joinToString(","))
    }
}

internal data class AndroidIpcPackageIdentity(
    val uid: Int,
    val packageName: String,
    val appLabel: String,
    val certificateDigests: List<String>,
)

internal fun captureAndroidIpcCaller(
    context: Context,
): AndroidIpcCaller? {
    val uid = Binder.getCallingUid()
    val pid = Binder.getCallingPid()
    val packageManager = context.packageManager
    val packageNames = packageManager
        .getPackagesForUid(uid)
        .orEmpty()
        .distinct()
    // A shared UID cannot be attributed to one application safely.
    val packageName = packageNames.singleOrNull()
    val identity = packageName?.let {
        resolveAndroidIpcPackageIdentity(
            context = context,
            packageName = it,
        )
    }
    return identity
        ?.takeIf { it.uid == uid }
        ?.let {
            AndroidIpcCaller(
                uid = uid,
                pid = pid,
                packageName = it.packageName,
                appLabel = it.appLabel,
                certificateDigests = it.certificateDigests,
            )
        }
}

internal fun resolveAndroidIpcPackageIdentity(
    context: Context,
    packageName: String,
): AndroidIpcPackageIdentity? {
    val packageManager = context.packageManager
    val certificateDigests = getSigningCertificateDigests(
        packageManager = packageManager,
        packageName = packageName,
    )
    val applicationInfo = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
    }.getOrNull()
    return if (applicationInfo != null && certificateDigests != null) {
        val appLabel = packageManager
            .getApplicationLabel(applicationInfo)
            .toString()
            .takeIf { it.isNotBlank() }
            ?: packageName
        AndroidIpcPackageIdentity(
            uid = applicationInfo.uid,
            packageName = packageName,
            appLabel = appLabel,
            certificateDigests = certificateDigests,
        )
    } else {
        null
    }
}

internal fun isCurrentAndroidIpcCaller(
    context: Context,
    caller: AndroidIpcCaller,
): Boolean {
    val identity = resolveAndroidIpcPackageIdentity(
        context = context,
        packageName = caller.packageName,
    ) ?: return false
    return identity.uid == caller.uid &&
            identity.certificateDigests == caller.certificateDigests
}

private fun getSigningCertificateDigests(
    packageManager: PackageManager,
    packageName: String,
): List<String>? = packageManager
    .getAndroidPackageSigningCertificates(packageName)
    ?.currentOrHistory
    ?.map { certificate ->
        sha256(certificate).toHex()
    }
    ?.distinct()
    ?.sorted()
    ?.takeIf { it.isNotEmpty() }
