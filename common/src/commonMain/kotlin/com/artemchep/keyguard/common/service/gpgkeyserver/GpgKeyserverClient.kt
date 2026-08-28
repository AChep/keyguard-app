package com.artemchep.keyguard.common.service.gpgkeyserver

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest

interface GpgKeyserverClient {
    fun search(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>>

    /**
     * Whether the effective keyserver protocol can directly answer [request].
     * A request-level [SearchGpgPublicKeyRequest.keyserverConfig] takes
     * precedence over [config].
     *
     * The VKS protocol only supports by-fingerprint, by-key-id and by-email
     * lookups, so a free-text query against a VKS keyserver returns `false`.
     * The client is a dumb transport and never silently re-routes such a
     * query to a different keyserver; instead the caller decides whether to
     * fall back to a protocol that supports free-text search (e.g. an HKP
     * index) and is thereby aware of the associated privacy tradeoff.
     */
    fun canServeSearch(
        request: SearchGpgPublicKeyRequest,
        config: GpgKeyserverConfig,
    ): Boolean

    /**
     * Returns `null` only when the keyserver reports that the certificate is absent.
     * Invalid, unsupported, or mismatched responses fail the returned [IO].
     */
    fun getByFingerprint(
        fingerprint: String,
        config: GpgKeyserverConfig,
    ): IO<DGpgKeyserverResult?>

    fun getByEmail(
        email: String,
        config: GpgKeyserverConfig,
    ): IO<List<DGpgKeyserverResult>>

    fun upload(
        publicKeyArmored: String,
        config: GpgKeyserverConfig,
    ): IO<Unit>
}
