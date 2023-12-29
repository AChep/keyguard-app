package com.artemchep.keyguard.common.model

data class AddUriCipherRequest(
    val cipherId: String,
    // autofill
    val applicationId: String? = null,
    val webDomain: String? = null,
    val webScheme: String? = null,
    val webView: Boolean? = null,
)
