package com.artemchep.keyguard.common.service.extract.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoExecute
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import kotlin.reflect.KClass

internal const val CMD_SCHEME_PREFIX = "cmd://"

class LinkInfoExtractorExecute(
) : LinkInfoExtractor<DSecret.Uri, LinkInfoExecute> {
    override val from: KClass<DSecret.Uri> get() = DSecret.Uri::class

    override val to: KClass<LinkInfoExecute> get() = LinkInfoExecute::class

    override fun extractInfo(
        uri: DSecret.Uri,
    ): IO<LinkInfoExecute> = ioEffect<LinkInfoExecute> {
        if (uri.uri.startsWith(CMD_SCHEME_PREFIX)) {
            val result = LinkInfoExecute.Allow(
                command = uri.uri.removePrefix(CMD_SCHEME_PREFIX),
            )
            return@ioEffect result
        }

        LinkInfoExecute.Deny
    }

    override fun handles(uri: DSecret.Uri): Boolean = true
}
