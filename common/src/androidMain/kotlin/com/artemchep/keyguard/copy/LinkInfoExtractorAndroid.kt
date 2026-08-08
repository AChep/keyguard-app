package com.artemchep.keyguard.copy

import android.content.pm.PackageManager
import androidx.collection.LruCache
import com.artemchep.keyguard.android.util.getAndroidPackageSigningCertificates
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.LinkInfoAndroid
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.util.normalizeSha256FingerprintOrNull
import com.artemchep.keyguard.common.util.toHex
import com.artemchep.keyguard.util.foundation.crypto.sha256
import com.google.accompanist.drawablepainter.DrawablePainter
import kotlin.reflect.KClass

class LinkInfoExtractorAndroid(
    private val packageManager: PackageManager,
) : LinkInfoExtractor<LinkInfoPlatform.Android, LinkInfoAndroid> {
    private val lruCache = LruCache<String, LinkInfoAndroid>(2)

    override val from: KClass<LinkInfoPlatform.Android> get() = LinkInfoPlatform.Android::class

    override val to: KClass<LinkInfoAndroid> get() = LinkInfoAndroid::class

    override fun extractInfo(
        uri: LinkInfoPlatform.Android,
    ): IO<LinkInfoAndroid> = ioEffect<LinkInfoAndroid> {
        val appInfo = synchronized(lruCache) {
            val cached = lruCache.get(uri.packageName)
            if (cached != null) {
                cached
            } else {
                val result = obtainInfo(
                    uri = uri,
                )
                lruCache.put(uri.packageName, result)
                result
            }
        }
        if (appInfo is LinkInfoAndroid.Installed) {
            // Do not cache signing identity: an app may be updated or replaced while
            // Keyguard's process remains alive.
            appInfo.copy(
                signingCertificates = obtainSigningCertificates(uri.packageName),
            )
        } else {
            appInfo
        }
    }

    private fun obtainInfo(
        uri: LinkInfoPlatform.Android,
    ) = kotlin.run {
        val appInfoNotInstalled = LinkInfoAndroid.NotInstalled(
            platform = uri,
        )
        val appInfo = try {
            val packageName = uri.packageName
            if (packageName.isEmpty()) {
                // no need to check if empty package is installed
                return@run appInfoNotInstalled
            }
            packageManager.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return@run appInfoNotInstalled
        }

        val label = packageManager.getApplicationLabel(appInfo)
        val icon = packageManager.getApplicationIcon(appInfo)
        LinkInfoAndroid.Installed(
            label = label.toString(),
            icon = DrawablePainter(icon),
            platform = uri,
        )
    }

    private fun obtainSigningCertificates(
        packageName: String,
    ): LinkInfoAndroid.SigningCertificates? = packageManager
        .getAndroidPackageSigningCertificates(packageName)
        ?.let { certificates ->
            LinkInfoAndroid.SigningCertificates(
                current = certificates.current
                    .mapTo(mutableSetOf()) { it.sha256Fingerprint() },
                history = certificates.history
                    .mapTo(mutableSetOf()) { it.sha256Fingerprint() },
                hasMultipleSigners = certificates.hasMultipleSigners,
            )
        }

    private fun ByteArray.sha256Fingerprint(): String =
        checkNotNull(
            sha256(this)
                .toHex()
                .normalizeSha256FingerprintOrNull(),
        )

    override fun handles(uri: LinkInfoPlatform.Android): Boolean = true
}
