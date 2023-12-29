package com.artemchep.keyguard.common.model

data class GeneratorContext(
    /**
     * Hostname of the web service we are currently
     * generating email for.
     */
    val host: String?,
)
