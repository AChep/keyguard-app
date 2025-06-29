package com.artemchep.keyguard.common.model

import kotlin.time.Instant

data class DGeneratorHistory(
    val id: String? = null,
    val value: GetPasswordResult,
    val createdDate: Instant,
    val isPassword: Boolean,
    val isUsername: Boolean,
    val isEmailRelay: Boolean,
    val isSshKey: Boolean,
)
