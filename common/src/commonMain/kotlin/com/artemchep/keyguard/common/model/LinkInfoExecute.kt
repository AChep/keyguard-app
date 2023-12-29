package com.artemchep.keyguard.common.model

/**
 * @author Artem Chepurnyi
 */
sealed interface LinkInfoExecute : LinkInfo {
    data class Allow(
        val command: String,
    ) : LinkInfoExecute

    data object Deny : LinkInfoExecute
}
