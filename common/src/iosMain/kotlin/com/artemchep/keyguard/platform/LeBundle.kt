package com.artemchep.keyguard.platform

actual data class LeBundle(
    val map: Map<String, Any?>,
)

actual operator fun LeBundle.contains(key: String): Boolean = key in map

actual operator fun LeBundle.get(key: String): Any? = map[key]

actual fun leBundleOf(
    vararg map: Pair<String, Any?>,
): LeBundle = LeBundle(map = map.toMap())
