package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.DGpgKeyserverResult
import com.artemchep.keyguard.common.model.GpgKeyserverConfig
import com.artemchep.keyguard.common.model.SearchGpgPublicKeyRequest
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverClient
import com.artemchep.keyguard.common.usecase.GetGpgKeyserverConfig
import com.artemchep.keyguard.common.usecase.SearchGpgPublicKey
import org.kodein.di.DirectDI
import org.kodein.di.instance

class SearchGpgPublicKeyImpl(
    private val getGpgKeyserverConfig: GetGpgKeyserverConfig,
    private val keyserverClient: GpgKeyserverClient,
) : SearchGpgPublicKey {
    constructor(
        directDI: DirectDI,
    ) : this(
        getGpgKeyserverConfig = directDI.instance(),
        keyserverClient = directDI.instance(),
    )

    override fun invoke(
        request: SearchGpgPublicKeyRequest,
    ): IO<List<DGpgKeyserverResult>> =
        getGpgKeyserverConfig()
            .toIO()
            .flatMap { config ->
                val effectiveConfig = request.keyserverConfig ?: config
                if (keyserverClient.canServeSearch(request, effectiveConfig)) {
                    keyserverClient.search(
                        request = request,
                        config = config,
                    )
                } else {
                    // The configured keyserver (VKS) has no free-text search
                    // endpoint, so a free-text query cannot be answered there.
                    // Fall back to the Ubuntu HKP index, which does.
                    //
                    // Privacy tradeoff: the user's search term leaves their
                    // configured keyserver and is sent to keyserver.ubuntu.com
                    // instead. This preserves the previous end-to-end behavior;
                    // the difference is that the re-route now happens here in
                    // plain sight rather than being hidden inside the transport
                    // client. Results stay attributed to their real origin via
                    // DGpgKeyserverResult.sourceKeyserver. The per-request
                    // keyserver overrides are dropped so the fallback always
                    // targets the Ubuntu HKP server, exactly as before.
                    keyserverClient.search(
                        request = request.copy(
                            keyserver = null,
                            keyserverConfig = null,
                        ),
                        config = GpgKeyserverConfig(
                            url = GpgKeyserverConfig.HKP_UBUNTU_URL,
                            protocol = GpgKeyserverConfig.Protocol.HKP,
                        ),
                    )
                }
            }
}
