package com.artemchep.keyguard.platform

/**
 * `true` if the app is not for the app store, so it
 * should not contain subscriptions, `false` if the app
 * is for the app store.
 */
expect val isStandalone: Boolean
