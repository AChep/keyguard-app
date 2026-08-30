package com.artemchep.keyguard.common.service.browseragent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface IpcRequest {
    @Serializable
    @SerialName("authenticate")
    data class Authenticate(
        val token: String,
    ) : IpcRequest

    @Serializable
    @SerialName("query")
    data class Query(
        val domain: String,
        val uri: String? = null,
    ) : IpcRequest

    @Serializable
    @SerialName("secret")
    data class Secret(
        @SerialName("item_id")
        val itemId: String,
    ) : IpcRequest

    @Serializable
    @SerialName("request_foreground")
    data class RequestForeground(
        val token: String? = null,
    ) : IpcRequest
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface IpcResponse {
    @Serializable
    @SerialName("authenticate")
    data class Authenticate(
        val success: Boolean,
    ) : IpcResponse

    @Serializable
    @SerialName("query")
    data class Query(
        val locked: Boolean = false,
        val items: List<AutofillItem> = emptyList(),
    ) : IpcResponse

    @Serializable
    @SerialName("secret")
    data class Secret(
        val locked: Boolean = false,
        val username: String? = null,
        val password: String? = null,
        val totp: String? = null,
    ) : IpcResponse

    @Serializable
    @SerialName("request_foreground")
    data class RequestForeground(
        val success: Boolean,
    ) : IpcResponse
}

@Serializable
data class AutofillItem(
    @SerialName("item_id")
    val itemId: String,
    val name: String,
    val username: String,
    @SerialName("has_totp")
    val hasTotp: Boolean = false,
    @SerialName("has_passkey")
    val hasPasskey: Boolean = false,
)

@Serializable
data class QueryResult(
    val locked: Boolean = false,
    val items: List<AutofillItem> = emptyList(),
)

@Serializable
data class SecretResult(
    val locked: Boolean = false,
    val username: String? = null,
    val password: String? = null,
    val totp: String? = null,
)
