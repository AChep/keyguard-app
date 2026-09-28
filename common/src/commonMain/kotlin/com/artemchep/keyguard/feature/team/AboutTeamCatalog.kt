package com.artemchep.keyguard.feature.team

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.team_artem_whoami_text

object AboutTeamCatalog {
    const val NAME = "Artem Chepurnyi"
    const val FLAG = "🇺🇦"
    val about = Res.string.team_artem_whoami_text

    val socialNetworks = listOf(
        AboutTeamSocialLink(AboutTeamSocialId.GITHUB, "GitHub", "AChep", "https://github.com/AChep/"),
        AboutTeamSocialLink(AboutTeamSocialId.MASTODON, "Mastodon", "artemchep", "https://mastodon.social/@artemchep"),
        AboutTeamSocialLink(AboutTeamSocialId.INSTAGRAM, "Instagram", "artemchep", "https://instagram.com/artemchep/"),
    )
}

enum class AboutTeamSocialId { GITHUB, MASTODON, INSTAGRAM }

data class AboutTeamSocialLink(
    val id: AboutTeamSocialId,
    val title: String,
    val username: String,
    val url: String,
)
