package com.artemchep.keyguard.feature.team

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.icons.icon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Github
import compose.icons.feathericons.Instagram
import org.jetbrains.compose.resources.painterResource

data class AboutTeamSocialNetwork(
    val title: String,
    val username: String,
    val icon: @Composable () -> Unit,
    val color: Color,
    val url: String,
)

@Composable
fun rememberAboutTeamSocialNetworks(): List<AboutTeamSocialNetwork> = remember {
    listOf(
        AboutTeamSocialNetwork(
            title = "GitHub",
            username = "AChep",
            icon = {
                icon(FeatherIcons.Github).invoke()
            },
            color = Color.Black,
            url = "https://github.com/AChep/",
        ),
        AboutTeamSocialNetwork(
            title = "Mastodon",
            username = "artemchep",
            icon = {
                Image(
                    modifier = Modifier
                        .size(24.dp)
                        .padding(1.dp),
                    painter = painterResource(Res.drawable.ic_mastodon),
                    contentDescription = null,
                )
            },
            color = Color(0xFF595AFF),
            url = "https://mastodon.social/@artemchep",
        ),
        AboutTeamSocialNetwork(
            title = "Instagram",
            username = "artemchep",
            icon = {
                icon(FeatherIcons.Instagram).invoke()
            },
            color = Color(0xFF8134AF),
            url = "https://instagram.com/artemchep/",
        ),
    )
}

@Composable
fun AboutTeamSocialNetworkAvatar(
    socialNetwork: AboutTeamSocialNetwork,
) {
    Avatar(
        color = socialNetwork.color,
    ) {
        val tint =
            if (socialNetwork.color.luminance() > 0.5f) Color.Black else Color.White
        CompositionLocalProvider(
            LocalContentColor provides tint,
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center,
            ) {
                socialNetwork.icon()
            }
        }
    }
}
