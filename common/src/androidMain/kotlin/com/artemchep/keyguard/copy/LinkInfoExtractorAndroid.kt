package com.artemchep.keyguard.copy

import android.content.pm.PackageManager
import androidx.collection.LruCache
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.LinkInfoAndroid
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
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
        synchronized(lruCache) {
            val cached = lruCache.get(uri.packageName)
            if (cached != null) {
                return@ioEffect cached
            }

            val result = obtainInfo(
                uri = uri,
            )
            lruCache.put(uri.packageName, result)
            result
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

    override fun handles(uri: LinkInfoPlatform.Android): Boolean = true
}
