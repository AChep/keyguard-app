package com.artemchep.keyguard.common.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

@Serializable
@JvmInline
value class Password(
    val value: String,
) {
    override fun toString(): String = "Password(<redacted>)"
}