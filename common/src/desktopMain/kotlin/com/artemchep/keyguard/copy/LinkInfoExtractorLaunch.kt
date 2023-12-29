package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoLaunch
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import kotlin.reflect.KClass

class LinkInfoExtractorLaunch(
) : LinkInfoExtractor<DSecret.Uri, LinkInfoLaunch> {
    override val from: KClass<DSecret.Uri> get() = DSecret.Uri::class

    override val to: KClass<LinkInfoLaunch> get() = LinkInfoLaunch::class

    override fun extractInfo(
        uri: DSecret.Uri,
    ): IO<LinkInfoLaunch> = ioEffect<LinkInfoLaunch> {
        val apps = LinkInfoLaunch.Allow.AppInfo(
            label = "App",
        )
        LinkInfoLaunch.Allow(listOf(apps))
    }

    override fun handles(uri: DSecret.Uri): Boolean = true
}
