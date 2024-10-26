package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import arrow.core.getOrElse
import com.artemchep.keyguard.URL_CROWDIN
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributor
import com.artemchep.keyguard.common.service.localizationcontributors.LocalizationContributorsService
import com.artemchep.keyguard.feature.localizationcontributors.directory.LocalizationContributorCrown
import com.artemchep.keyguard.feature.localizationcontributors.directory.LocalizationContributorsListRoute
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.icons.UserIcon
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val CONTRIBUTORS_TOP_SIZE = 5

fun settingLocalizationProvider(
    directDI: DirectDI,
): SettingComponent = settingLocalizationProvider(
    localizationContributorsService = directDI.instance(),
)

fun settingLocalizationProvider(
    localizationContributorsService: LocalizationContributorsService,
): SettingComponent = kotlin.run {
    val item = SettingIi(
        search = SettingIi.Search(
            group = "about",
            tokens = listOf(
                "localization",
                "language",
                "translate",
                "crowdin",
            ),
        ),
    ) {
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        SettingLocalization(
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = URL_CROWDIN,
                )
                navigationController.queue(intent)
            },
        )

        // Show top localization contributors
        val topLocalizationContributorsState = remember {
            mutableStateOf<List<LocalizationContributor>?>(null)
        }
        LaunchedEffect(topLocalizationContributorsState) {
            val contributorsResult = localizationContributorsService.get()
                .attempt()
                .bind()
            topLocalizationContributorsState.value = contributorsResult
                .getOrElse { emptyList() }
                .take(CONTRIBUTORS_TOP_SIZE)
        }
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(
                modifier = Modifier
                    .width(40.dp),
            )

            val list = topLocalizationContributorsState.value
            if (list == null) {
                repeat(CONTRIBUTORS_TOP_SIZE) {
                    val backgroundColor = LocalContentColor.current
                        .combineAlpha(DisabledEmphasisAlpha)
                    Avatar {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp)
                                .clip(CircleShape)
                                .shimmer()
                                .background(backgroundColor),
                        )
                    }
                }
            } else {
                list.forEachIndexed { index, contributor ->
                    BadgedBox(
                        badge = {
                            if (index <= 2) LocalizationContributorCrown(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape),
                                index = index,
                            )
                        },
                    ) {
                        Avatar {
                            UserIcon(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(3.dp)
                                    .clip(CircleShape),
                                pictureUrl = contributor.user.avatarUrl,
                            )
                        }
                    }
                }
            }

            TextButton(
                onClick = {
                    val intent = NavigationIntent.NavigateToRoute(LocalizationContributorsListRoute)
                    navigationController.queue(intent)
                },
            ) {
                Text(
                    text = stringResource(Res.string.pref_item_crowdin_view_all_contributors_title),
                )
            }

            Spacer(
                modifier = Modifier
                    .width(16.dp),
            )
        }
    }
    flowOf(item)
}

@Composable
private fun SettingLocalization(
    onClick: (() -> Unit)?,
) {
    FlatItem(
        leading = icon<RowScope>(Icons.Outlined.Translate, Icons.Outlined.KeyguardWebsite),
        trailing = {
            ChevronIcon()
        },
        title = {
            Text(
                text = stringResource(Res.string.pref_item_crowdin_title),
            )
        },
        onClick = onClick,
    )
}
