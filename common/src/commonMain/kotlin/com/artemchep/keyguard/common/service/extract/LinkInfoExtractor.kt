package com.artemchep.keyguard.common.service.extract

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.LinkInfo
import kotlin.reflect.KClass

interface LinkInfoExtractor<From, To> where From : LinkInfo, To : LinkInfo {
    val from: KClass<From>
    val to: KClass<To>

    fun extractInfo(
        uri: From,
    ): IO<To>

    fun handles(uri: From): Boolean
}
