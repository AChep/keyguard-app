package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

data class CheckWebDavConnectionRequest(
    val url: String,
    val username: String?,
    val password: String?,
)

interface CheckWebDavConnection : (CheckWebDavConnectionRequest) -> IO<Unit>
