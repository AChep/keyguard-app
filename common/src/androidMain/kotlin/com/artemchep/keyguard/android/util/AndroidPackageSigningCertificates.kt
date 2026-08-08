package com.artemchep.keyguard.android.util

import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi

internal data class AndroidPackageSigningCertificates(
    val current: List<ByteArray>,
    val history: List<ByteArray>,
    val hasMultipleSigners: Boolean,
) {
    val currentOrHistory: List<ByteArray>
        get() = if (hasMultipleSigners) current else history
}

internal fun PackageManager.getAndroidPackageSigningCertificates(
    packageName: String,
): AndroidPackageSigningCertificates? = runCatching {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
            getAndroidPackageSigningCertificatesOrThrowFor28(
                packageName = packageName,
            )
        }

        else -> {
            getAndroidPackageSigningCertificatesOrThrowFor26(
                packageName = packageName,
            )
        }
    }
}.getOrNull()

@RequiresApi(Build.VERSION_CODES.P)
private fun PackageManager.getAndroidPackageSigningCertificatesOrThrowFor28(
    packageName: String,
): AndroidPackageSigningCertificates {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(
                PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
            ),
        )
    } else {
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }
    val signingInfo = requireNotNull(packageInfo.signingInfo)
    val hasMultipleSigners = signingInfo.hasMultipleSigners()
    val current = signingInfo.apkContentsSigners
        .orEmpty()
        .map { signature -> signature.toByteArray() }
    val history = if (hasMultipleSigners) {
        emptyList()
    } else {
        signingInfo.signingCertificateHistory
            .orEmpty()
            .map { signature -> signature.toByteArray() }
    }
    return AndroidPackageSigningCertificates(
        current = current,
        history = history,
        hasMultipleSigners = hasMultipleSigners,
    )
}

@Suppress("DEPRECATION")
private fun PackageManager.getAndroidPackageSigningCertificatesOrThrowFor26(
    packageName: String,
): AndroidPackageSigningCertificates {
    val current = getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        .signatures
        .orEmpty()
        .map { signature -> signature.toByteArray() }
    return AndroidPackageSigningCertificates(
        current = current,
        history = current,
        hasMultipleSigners = current.size > 1,
    )
}
