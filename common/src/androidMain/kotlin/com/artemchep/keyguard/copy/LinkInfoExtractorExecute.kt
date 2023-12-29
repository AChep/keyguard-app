package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoExecute
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import kotlin.reflect.KClass

class LinkInfoExtractorExecute(
) : LinkInfoExtractor<DSecret.Uri, LinkInfoExecute> {
    override val from: KClass<DSecret.Uri> get() = DSecret.Uri::class

    override val to: KClass<LinkInfoExecute> get() = LinkInfoExecute::class

    override fun extractInfo(
        uri: DSecret.Uri,
    ): IO<LinkInfoExecute> = io(LinkInfoExecute.Deny)

    override fun handles(uri: DSecret.Uri): Boolean = true
}
