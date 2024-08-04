package com.artemchep.keyguard.common.service.download

import java.io.File

fun interface CacheDirProvider {
    suspend fun get(): File
}