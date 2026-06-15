package com.artemchep.keyguard.wear.util

private const val BULLET_SEPARATOR = " • "

internal fun joinToBulletStringOrNull(
    value1: String?,
): String? = value1?.takeIf { it.isNotBlank() }

internal fun joinToBulletStringOrNull(
    value1: String?,
    value2: String?,
): String? {
    val normalizedValue1 = value1?.takeIf { it.isNotBlank() }
    val normalizedValue2 = value2?.takeIf { it.isNotBlank() }
    return when {
        normalizedValue1 == null -> normalizedValue2
        normalizedValue2 == null -> normalizedValue1
        else -> "$normalizedValue1$BULLET_SEPARATOR$normalizedValue2"
    }
}

internal fun joinToBulletStringOrNull(
    value1: String?,
    value2: String?,
    value3: String?,
): String? {
    val normalizedValue1 = value1?.takeIf { it.isNotBlank() }
    val normalizedValue2 = value2?.takeIf { it.isNotBlank() }
    val normalizedValue3 = value3?.takeIf { it.isNotBlank() }

    return when {
        normalizedValue1 != null && normalizedValue2 != null && normalizedValue3 != null ->
            "$normalizedValue1$BULLET_SEPARATOR$normalizedValue2$BULLET_SEPARATOR$normalizedValue3"

        normalizedValue1 != null && normalizedValue2 != null ->
            "$normalizedValue1$BULLET_SEPARATOR$normalizedValue2"

        normalizedValue1 != null && normalizedValue3 != null ->
            "$normalizedValue1$BULLET_SEPARATOR$normalizedValue3"

        normalizedValue2 != null && normalizedValue3 != null ->
            "$normalizedValue2$BULLET_SEPARATOR$normalizedValue3"

        else -> normalizedValue1 ?: normalizedValue2 ?: normalizedValue3
    }
}
