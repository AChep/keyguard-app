package com.artemchep.keyguard.feature.navigation.backpress

interface BackPressInterceptorHost {
    /**
     * Invoke to add the back press interceptor. The [block] function will be
     * called instead of altering the navigation stack. To remove the interceptor
     * call the returned lambda.
     */
    fun interceptBackPress(
        block: () -> Unit,
    ): () -> Unit
}
