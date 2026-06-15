package com.artemchep.keyguard.feature.team

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.team_artem_whoami_text
import org.jetbrains.compose.resources.stringResource

data class AboutTeamContent(
    val name: String,
    val flag: String,
    val about: String,
    val thanks: String,
)

@Composable
fun rememberAboutTeamContent(): AboutTeamContent {
    val about = stringResource(Res.string.team_artem_whoami_text)
    return remember(about) {
        AboutTeamContent(
            name = "Artem Chepurnyi",
            flag = "\uD83C\uDDFA\uD83C\uDDE6",
            about = about,
            thanks = "Thanks you my friends for supporting me and patiently testing the app.",
        )
    }
}
