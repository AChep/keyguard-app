package com.artemchep.keyguard.feature.team

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.resources.stringResource

data class AboutTeamContent(
    val name: String,
    val flag: String,
    val about: String,
    val thanks: String,
)

@Composable
fun rememberAboutTeamContent(): AboutTeamContent {
    val about = stringResource(AboutTeamCatalog.about)
    return remember(about) {
        AboutTeamContent(
            name = AboutTeamCatalog.NAME,
            flag = AboutTeamCatalog.FLAG,
            about = about,
            thanks = "Thanks you my friends for supporting me and patiently testing the app.",
        )
    }
}
