@file:JvmName("PasswordStrengthJvm")

package com.artemchep.keyguard.common.model

import androidx.compose.runtime.Composable
import arrow.optics.optics
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.compose.stringResource

@optics
data class PasswordStrength(
    val crackTimeSeconds: Long,
    val score: Score = when {
        crackTimeSeconds <= 1000L -> Score.Weak
        crackTimeSeconds <= 100000L -> Score.Fair
        crackTimeSeconds <= 100000000L -> Score.Good
        crackTimeSeconds <= 100000000000L -> Score.Strong
        else -> Score.VeryStrong
    },
    val version: Long,
) {
    companion object;

    enum class Score {
        Weak,
        Fair,
        Good,
        Strong,
        VeryStrong,
    }
}

fun PasswordStrength.Score.alertScore() = when (this) {
    PasswordStrength.Score.Weak -> 0f
    PasswordStrength.Score.Fair -> 0.2f
    PasswordStrength.Score.Good -> 0.5f
    PasswordStrength.Score.Strong -> 0.9f
    PasswordStrength.Score.VeryStrong -> 1f
}

fun PasswordStrength.Score.formatH2() = when (this) {
    PasswordStrength.Score.Weak -> Res.strings.passwords_weak_label
    PasswordStrength.Score.Fair -> Res.strings.passwords_fair_label
    PasswordStrength.Score.Good -> Res.strings.passwords_good_label
    PasswordStrength.Score.Strong -> Res.strings.passwords_strong_label
    PasswordStrength.Score.VeryStrong -> Res.strings.passwords_very_strong_label
}

fun PasswordStrength.Score.formatH() = when (this) {
    PasswordStrength.Score.Weak -> Res.strings.password_strength_weak_label
    PasswordStrength.Score.Fair -> Res.strings.password_strength_fair_label
    PasswordStrength.Score.Good -> Res.strings.password_strength_good_label
    PasswordStrength.Score.Strong -> Res.strings.password_strength_strong_label
    PasswordStrength.Score.VeryStrong -> Res.strings.password_strength_very_strong_label
}

@Composable
fun PasswordStrength.Score.formatLocalized() = stringResource(formatH())
