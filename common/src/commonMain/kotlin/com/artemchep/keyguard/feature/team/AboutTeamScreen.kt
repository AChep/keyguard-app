package com.artemchep.keyguard.feature.team

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.home.vault.component.LargeSection
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import compose.icons.FeatherIcons
import compose.icons.feathericons.Github
import compose.icons.feathericons.Instagram
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.compose.resources.painterResource

private data class SocialNetwork(
    val title: String,
    val username: String,
    val icon: @Composable () -> Unit,
    val color: Color,
    val url: String,
)

private data class SocialNetworkItem(
    val title: String,
    val username: String,
    val leading: @Composable RowScope.() -> Unit,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutTeamScreen() {
    val navController by rememberUpdatedState(LocalNavigationController.current)
    val socialNetworks = remember {
        listOf(
            SocialNetwork(
                title = "GitHub",
                username = "AChep",
                icon = icon(FeatherIcons.Github),
                color = Color.Black,
                url = "https://github.com/AChep/",
            ),
            SocialNetwork(
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
                color = Color(0xFF595aff),
                url = "https://mastodon.social/@artemchep",
            ),
            SocialNetwork(
                title = "Instagram",
                username = "artemchep",
                icon = icon(FeatherIcons.Instagram),
                color = Color(0xFF8134AF),
                url = "https://instagram.com/artemchep/",
            ),
        ).map { socialNetwork ->
            SocialNetworkItem(
                title = socialNetwork.title,
                username = socialNetwork.username,
                leading = {
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
                            ) {
                                socialNetwork.icon()
                            }
                        }
                    }
                },
                onClick = {
                    val intent = NavigationIntent.NavigateToBrowser(socialNetwork.url)
                    navController.queue(intent)
                },
            )
        }.toPersistentList()
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.team_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        LargeSection(
            text = "Artem Chepurnyi",
            trailing = {
                Text(
                    text = "\uD83C\uDDFA\uD83C\uDDE6",
                )
            },
        )
        Text(
            modifier = Modifier
                .padding(horizontal = 16.dp),
            text = stringResource(Res.string.team_artem_whoami_text),
        )
        Section(
            text = stringResource(Res.string.team_follow_me_section),
        )
        socialNetworks.forEach { item ->
            FlatItem(
                leading = item.leading,
                trailing = {
                    ChevronIcon()
                },
                title = {
                    Text(item.title)
                },
                onClick = item.onClick,
            )
        }
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = "Thanks to Vira & my friends for supporting me and patiently testing the app.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        /*
        LargeSection(
            text = "Community",
        )
        Section(
            text = "Localization",
        )
        SkeletonItem()
        SkeletonItem()
        SkeletonItem()
         */
    }
}
