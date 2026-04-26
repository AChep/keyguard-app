package com.artemchep.keyguard.wear.feature.team

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.LocalContentColor
import com.artemchep.keyguard.feature.team.rememberAboutTeamContent
import com.artemchep.keyguard.feature.home.vault.component.LargeSection
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.team_header_title
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.WearScaffoldColumn
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearAboutTeamScreen() {
    val content = rememberAboutTeamContent()
    WearScaffoldColumn(
        title = stringResource(Res.string.team_header_title),
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
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = content.thanks,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}
