package com.artemchep.keyguard.copy

import android.content.Intent
import android.content.pm.PackageManager
import androidx.collection.LruCache
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoLaunch
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.google.accompanist.drawablepainter.DrawablePainter
import kotlin.reflect.KClass
import androidx.core.net.toUri

class LinkInfoExtractorLaunch(
    private val packageManager: PackageManager,
) : LinkInfoExtractor<DSecret.Uri, LinkInfoLaunch> {
    private val lruCache = LruCache<String, LinkInfoLaunch>(2)

    override val from: KClass<DSecret.Uri> get() = DSecret.Uri::class

    override val to: KClass<LinkInfoLaunch> get() = LinkInfoLaunch::class

    override fun extractInfo(
        uri: DSecret.Uri,
    ): IO<LinkInfoLaunch> = ioEffect<LinkInfoLaunch> {
        synchronized(lruCache) {
            val cached = lruCache.get(uri.uri)
            if (cached != null) {
                return@ioEffect cached
            }

            val result = obtainInfo(
                uri = uri.uri,
            )
            lruCache.put(uri.uri, result)
            result
        }
    }

    private fun obtainInfo(
        uri: String,
    ): LinkInfoLaunch = kotlin.run {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri.toUri()
        }
        val apps = packageManager
            .queryIntentActivities(intent, 0)
            .map { resolveInfo ->
                val label = resolveInfo.loadLabel(packageManager)
                    .toString()
                val icon = resolveInfo.loadIcon(packageManager)
                LinkInfoLaunch.Allow.AppInfo(
                    label = label,
                    icon = DrawablePainter(icon),
                )
            }
        when {
            apps.isEmpty() -> LinkInfoLaunch.Deny
            else -> LinkInfoLaunch.Allow(apps)
        }
    }

    override fun handles(uri: DSecret.Uri): Boolean = true
}
