package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.LinkInfoAndroid
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor

class AndroidLinkInfoExtractorRegistry(
    val values: List<LinkInfoExtractor<LinkInfoPlatform.Android, LinkInfoAndroid>>,
)
