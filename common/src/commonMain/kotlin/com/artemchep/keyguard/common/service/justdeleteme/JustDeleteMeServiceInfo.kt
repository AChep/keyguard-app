package com.artemchep.keyguard.common.service.justdeleteme

data class JustDeleteMeServiceInfo(
    val name: String,
    val domains: Set<String> = emptySet(),
    val url: String? = null,
    val difficulty: String? = null,
    val notes: String? = null,
    val email: String? = null,
    val emailSubject: String? = null,
    val emailBody: String? = null,
)
