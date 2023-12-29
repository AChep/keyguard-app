package com.artemchep.keyguard.platform

expect class LeBundle

expect operator fun LeBundle.contains(key: String): Boolean

expect operator fun LeBundle.get(key: String): Any?

expect fun leBundleOf(
    vararg map: Pair<String, Any?>,
): LeBundle
