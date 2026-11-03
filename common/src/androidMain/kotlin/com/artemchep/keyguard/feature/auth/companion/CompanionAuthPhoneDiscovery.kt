package com.artemchep.keyguard.feature.auth.companion

sealed interface CompanionAuthPhoneDiscovery {
    val devices: List<CompanionAuthPhoneDevice>
        get() = emptyList()

    data object Loading : CompanionAuthPhoneDiscovery

    data object Unavailable : CompanionAuthPhoneDiscovery

    data class Ready(
        override val devices: List<CompanionAuthPhoneDevice>,
    ) : CompanionAuthPhoneDiscovery
}
