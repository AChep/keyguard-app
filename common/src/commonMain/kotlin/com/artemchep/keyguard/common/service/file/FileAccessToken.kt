package com.artemchep.keyguard.common.service.file

import kotlinx.serialization.Serializable

import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class FileAccessToken(
    val value: String,
)
