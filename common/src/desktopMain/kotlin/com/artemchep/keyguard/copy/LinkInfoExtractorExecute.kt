package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoExecute
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import kotlin.reflect.KClass

class LinkInfoExtractorExecute(
) : LinkInfoExtractor<DSecret.Uri, LinkInfoExecute> {
    companion object {
        private const val PREFIX = "cmd://"
    }

    override val from: KClass<DSecret.Uri> get() = DSecret.Uri::class

    override val to: KClass<LinkInfoExecute> get() = LinkInfoExecute::class

    override fun extractInfo(
        uri: DSecret.Uri,
    ): IO<LinkInfoExecute> = ioEffect<LinkInfoExecute> {
        if (uri.uri.startsWith(PREFIX)) {
            val result = LinkInfoExecute.Allow(
                command = uri.uri.removePrefix(PREFIX),
            )
            return@ioEffect result
        }

        LinkInfoExecute.Deny
    }

    override fun handles(uri: DSecret.Uri): Boolean = true
}
