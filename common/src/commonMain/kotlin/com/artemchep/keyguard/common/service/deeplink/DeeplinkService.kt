package com.artemchep.keyguard.common.service.deeplink

import kotlinx.coroutines.flow.Flow

interface DeeplinkService {
    companion object {
        const val CUSTOM_FILTER = "customFilter"
        const val CUSTOM_HOME = "customHome"
    }

    fun get(key: String): String?

    fun getFlow(key: String): Flow<String?>

    fun put(key: String, value: String?)

    fun clear(key: String)
}
