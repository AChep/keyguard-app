package com.artemchep.keyguard.copy

import android.content.pm.PackageManager
import android.os.Build
import androidx.collection.LruCache
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.LinkInfoAndroid
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.util.normalizeSha256FingerprintOrNull
import com.artemchep.keyguard.common.util.toHex
import com.google.accompanist.drawablepainter.DrawablePainter
import java.security.MessageDigest
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
        } catch (e: PackageManager.NameNotFoundException) {
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

    @Suppress("DEPRECATION")
    private fun obtainSigningCertificates(
        packageName: String,
    ): LinkInfoAndroid.SigningCertificates? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val signingInfo = requireNotNull(packageInfo.signingInfo)
            val hasMultipleSigners = signingInfo.hasMultipleSigners()
            val current = signingInfo.apkContentsSigners
                .orEmpty()
                .mapTo(mutableSetOf()) { signature ->
                    signature.toByteArray().sha256Fingerprint()
                }
            val history = if (hasMultipleSigners) {
                emptySet()
            } else {
                signingInfo.signingCertificateHistory
                    .orEmpty()
                    .mapTo(mutableSetOf()) { signature ->
                        signature.toByteArray().sha256Fingerprint()
                    }
            }
            LinkInfoAndroid.SigningCertificates(
                current = current,
                history = history,
                hasMultipleSigners = hasMultipleSigners,
            )
        } else {
            val packageInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNATURES,
            )
            val current = packageInfo.signatures
                .orEmpty()
                .mapTo(mutableSetOf()) { signature ->
                    signature.toByteArray().sha256Fingerprint()
                }
            LinkInfoAndroid.SigningCertificates(
                current = current,
                history = current,
                hasMultipleSigners = current.size > 1,
            )
        }
    }.getOrNull()

    private fun ByteArray.sha256Fingerprint(): String =
        checkNotNull(
            MessageDigest
                .getInstance("SHA-256")
                .digest(this)
                .toHex()
                .normalizeSha256FingerprintOrNull(),
        )

    override fun handles(uri: LinkInfoPlatform.Android): Boolean = true
}
