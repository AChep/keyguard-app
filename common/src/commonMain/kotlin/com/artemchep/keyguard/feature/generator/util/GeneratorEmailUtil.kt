package com.artemchep.keyguard.feature.generator.util

import com.artemchep.keyguard.common.model.DProfile

fun List<DProfile>.findBestUserEmailOrNull(): String? {
    data class Entry(
        val email: String,
        val priority: Double,
    )

    val emailOrNull = this
        .asSequence()
        .map { profile ->
            val priority = kotlin.run {
                var p = 0.0
                if (profile.emailVerified) p += 10.0
                p
            }
            Entry(
                email = profile.email,
                priority = priority,
            )
        }
        .groupBy { it.email }
        .mapValues { entry ->
            entry.value
                .sumOf { it.priority }
        }
        .maxByOrNull { it.key }
        ?.key
    return emailOrNull
}
