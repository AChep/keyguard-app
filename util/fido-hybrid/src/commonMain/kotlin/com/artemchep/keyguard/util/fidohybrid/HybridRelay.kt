package com.artemchep.keyguard.util.fidohybrid

/** The advertised domain ID and the WebSocket destination must describe the same relay. */
enum class HybridRelay(
    val domainId: Int,
    private val host: String,
) {
    GOOGLE(0, "cable.ua5v.com"),
    ;

    fun newTunnelUrl(id: String): String {
        require(id.length == TUNNEL_ID_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' })
        return "wss://$host/cable/new/$id"
    }
}

private const val TUNNEL_ID_LENGTH = 32

fun interface HybridTunnelConnector {
    suspend fun connect(
        relay: HybridRelay,
        id: String,
        block: suspend (HybridTunnel) -> Unit,
    )
}
