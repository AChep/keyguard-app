package com.artemchep.keyguard.feature.justdeleteme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.Ah
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun AhDifficulty(
    modifier: Modifier = Modifier,
    model: JustDeleteMeServiceInfo,
) {
    val difficulty = model.difficulty
    val score = when (difficulty) {
        "easy" -> 1f
        "medium" -> 0.5f
        "hard" -> 0.2f
        "limited" -> 0.0f
        "impossible" -> 0.0f
        else -> 0.0f
    }
    val text = when (difficulty) {
        "easy" -> stringResource(Res.strings.justdeleteme_difficulty_easy_label)
        "medium" -> stringResource(Res.strings.justdeleteme_difficulty_medium_label)
        "hard" -> stringResource(Res.strings.justdeleteme_difficulty_hard_label)
        "limited" -> stringResource(Res.strings.justdeleteme_difficulty_limited_availability_label)
        "impossible" -> stringResource(Res.strings.justdeleteme_difficulty_impossible_label)
        else -> ""
    }
    Ah(
        modifier = modifier,
        score = score,
        text = text,
    )
}
