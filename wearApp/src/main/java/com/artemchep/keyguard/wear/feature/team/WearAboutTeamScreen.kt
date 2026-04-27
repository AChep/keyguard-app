package com.artemchep.keyguard.wear.feature.team

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.team.rememberAboutTeamContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.team_header_title
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearAboutTeamScreen() {
    val content = rememberAboutTeamContent()
    WearScaffoldScreen(
        title = stringResource(Res.string.team_header_title),
    ) { transformationSpec ->
        item("profile") {
            val surfaceTransformation = SurfaceTransformation(transformationSpec)
            WearListCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                icon = {
                    Text(
                        text = content.flag,
                    )
                },
                title = {
                    Text(
                        text = content.name,
                    )
                },
                text = {
                    Text(
                        text = content.about,
                    )
                },
                transformation = surfaceTransformation,
            )
        }
        item("thanks") {
            val surfaceTransformation = SurfaceTransformation(transformationSpec)
            WearListLabel(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                textAlign = TextAlign.Start,
                text = content.thanks,
                transformation = surfaceTransformation,
            )
        }
    }
}
