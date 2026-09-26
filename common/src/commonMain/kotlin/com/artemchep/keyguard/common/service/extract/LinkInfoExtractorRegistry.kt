package com.artemchep.keyguard.common.service.extract

import com.artemchep.keyguard.common.model.LinkInfo

class LinkInfoExtractorRegistry(
    values: List<LinkInfoExtractor<out LinkInfo, out LinkInfo>>,
) {
    // LinkInfoRegistry checks each extractor's declared input type before invoking it.
    @Suppress("UNCHECKED_CAST")
    val values = values as List<LinkInfoExtractor<LinkInfo, LinkInfo>>
}

class PlatformLinkInfoExtractorRegistry(
    val values: List<LinkInfoExtractor<out LinkInfo, out LinkInfo>>,
)
