package com.artemchep.keyguard.common.service.flavor

data class FlavorConfig(
    /**
     * `true` if the app is not for the app store, so it
     * should not contain subscriptions, `false` if the app
     * is for the app store.
     */
    val isFreeAsBeer: Boolean,
)