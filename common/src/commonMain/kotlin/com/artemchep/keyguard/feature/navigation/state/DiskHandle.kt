package com.artemchep.keyguard.feature.navigation.state

import kotlinx.coroutines.flow.Flow

/**
 * @author Artem Chepurnyi
 */
interface DiskHandle {
    val restoredState: Map<String, Any?>

    fun link(key: String, flow: Flow<Any?>)

    fun unlink(key: String)
}
