package com.artemchep.keyguard.feature.team

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.LargeSection
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.search.search.mapListShape
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import kotlinx.collections.immutable.toPersistentList
import org.jetbrains.compose.resources.stringResource

private data class SocialNetworkItem(
    val title: String,
    val username: String,
    val shapeState: Int = ShapeState.ALL,
    val leading: @Composable RowScope.() -> Unit,
    val onClick: () -> Unit,
) : GroupableShapeItem<SocialNetworkItem> {
    override fun withShape(shape: Int) = copy(shapeState = shape)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutTeamScreen() {
    val navController by rememberUpdatedState(LocalNavigationController.current)
    val content = rememberAboutTeamContent()
    val socialNetworks = rememberAboutTeamSocialNetworks()
    val items = socialNetworks
        .map { socialNetwork ->
            SocialNetworkItem(
                title = socialNetwork.title,
                username = socialNetwork.username,
                leading = {
                    AboutTeamSocialNetworkAvatar(socialNetwork)
                },
                onClick = {
                    val intent = NavigationIntent.NavigateToBrowser(socialNetwork.url)
                    navController.queue(intent)
                },
            )
        }
        .mapListShape()
        .toPersistentList()

    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
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
            text = content.name,
            trailing = {
                Text(
                    text = content.flag,
                )
            },
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = content.about,
        )
        Section(
            text = stringResource(Res.string.team_follow_me_section),
        )
        items.forEach { item ->
            FlatItemSimpleExpressive(
                leading = item.leading,
                shapeState = item.shapeState,
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
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = content.thanks,
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
