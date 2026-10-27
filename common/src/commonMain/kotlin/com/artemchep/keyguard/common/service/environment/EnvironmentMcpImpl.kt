package com.artemchep.keyguard.common.service.environment

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.Files
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The side of MCP that a platform runtime drives: it describes how MCP
 * clients start the CLI of the build. Screens use [EnvironmentMcp] instead.
 */
interface EnvironmentMcpHost : EnvironmentMcp {
    fun configure(command: String, arguments: List<String> = emptyList())
}

class EnvironmentMcpImpl(
    private val access: EnvironmentAccessImpl,
    storeFactory: KeyValueStoreFactory,
    private val json: Json,
    scope: CoroutineScope,
) : EnvironmentMcpHost {
    private val preference = storeFactory.get(Files.SETTINGS).getBoolean("environments.mcp.enabled", false)

    private val mutableState = MutableStateFlow(EnvironmentMcpState())
    override val state = mutableState.asStateFlow()

    init {
        scope.launch {
            preference.collect { enabled ->
                val previous = mutableState.value.enabled
                mutableState.update { it.copy(enabled = enabled) }
                if (previous != enabled) access.revoke(EnvironmentRevocation.ConfigurationChanged)
            }
        }
    }

    override fun configure(command: String, arguments: List<String>) {
        val configuration = json.encodeToString(buildJsonObject {
            put("mcpServers", buildJsonObject {
                put("keyguard", buildJsonObject {
                    put("command", JsonPrimitive(command))
                    if (arguments.isNotEmpty()) {
                        put("args", JsonArray(arguments.map(::JsonPrimitive)))
                    }
                })
            })
        })
        mutableState.update { it.copy(configuration = configuration) }
    }

    override suspend fun setEnabled(enabled: Boolean) {
        preference.setAndCommit(enabled).bind()
        mutableState.update { it.copy(enabled = enabled) }
        access.revoke(EnvironmentRevocation.ConfigurationChanged)
    }
}
