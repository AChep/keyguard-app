package com.artemchep.keyguard.common.service.justgetmydata

data class JustGetMyDataServiceInfo(
    val name: String,
    val domains: Set<String> = emptySet(),
    val url: String? = null,
    val difficulty: String? = null,
    val notes: String? = null,
    val email: String? = null,
    val emailSubject: String? = null,
    val emailBody: String? = null,
)
