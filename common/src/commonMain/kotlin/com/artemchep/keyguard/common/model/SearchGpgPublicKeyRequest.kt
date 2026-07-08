package com.artemchep.keyguard.common.model

data class SearchGpgPublicKeyRequest(
    val query: String,
    val mode: Mode = Mode.AUTO,
    /**
     * The keyserver to query, or `null` to use the configured default.
     *
     * Prefer [keyserverConfig] when following a result from a previous lookup,
     * because the URL alone does not identify which protocol to use.
     */
    val keyserver: String? = null,
    /**
     * The full keyserver endpoint to query, or `null` to use the configured
     * default. Takes precedence over [keyserver].
     */
    val keyserverConfig: GpgKeyserverConfig? = null,
) {
    enum class Mode {
        /**
         * Infer the lookup type from the [query] (fingerprint vs key-id vs
         * e-mail vs free text).
         */
        AUTO,
        FINGERPRINT,
        KEY_ID,
        EMAIL,
        TEXT,
    }
}
