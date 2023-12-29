package com.artemchep.keyguard.common.model

import kotlinx.datetime.Instant

data class DGeneratorHistory(
    val id: String? = null,
    val value: String,
    val createdDate: Instant,
    val isPassword: Boolean,
    val isUsername: Boolean,
    val isEmailRelay: Boolean,
)
