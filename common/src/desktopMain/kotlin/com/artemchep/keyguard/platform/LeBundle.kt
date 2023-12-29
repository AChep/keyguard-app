package com.artemchep.keyguard.platform

actual class LeBundle

actual operator fun LeBundle.contains(key: String): Boolean = false

actual operator fun LeBundle.get(key: String): Any? = null

actual fun leBundleOf(
    vararg map: Pair<String, Any?>,
): LeBundle = LeBundle()
