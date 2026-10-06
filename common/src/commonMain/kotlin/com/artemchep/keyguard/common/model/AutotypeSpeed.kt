package com.artemchep.keyguard.common.model

private const val FAST_DELAY_MULTIPLIER = 1
private const val NORMAL_DELAY_MULTIPLIER = 2
private const val SLOW_DELAY_MULTIPLIER = 4

enum class AutotypeSpeed(
    val storageKey: String,
    val delayMultiplier: Int,
) {
    Fast("fast", FAST_DELAY_MULTIPLIER),
    Normal("normal", NORMAL_DELAY_MULTIPLIER),
    Slow("slow", SLOW_DELAY_MULTIPLIER),
    ;

    companion object {
        fun fromStorageKey(value: String): AutotypeSpeed =
            entries.firstOrNull { it.storageKey == value } ?: Fast
    }
}
