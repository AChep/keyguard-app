package com.artemchep.keyguard.common.model

data class AnyMap(
    val value: Map<String, Any?>,
) : Map<String, Any?> by value
