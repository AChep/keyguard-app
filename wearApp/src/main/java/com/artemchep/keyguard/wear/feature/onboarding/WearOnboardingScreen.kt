package com.artemchep.keyguard.wear.feature.onboarding

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import com.artemchep.keyguard.feature.onboarding.SmallOnboardingCard
import com.artemchep.keyguard.feature.onboarding.onboardingSections
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.feat_header_title
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import kotlinx.coroutines.GlobalScope
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WearOnboardingScreen() {
    val putInstant by rememberInstance<PutOnboardingLastVisitInstant>()
    LaunchedEffect(putInstant) {
        putInstant(Clock.System.now())
            .attempt()
            .launchIn(GlobalScope)
    }

    WearScaffoldScreen(
        title = stringResource(Res.string.feat_header_title),
    ) { transformationSpec ->
        onboardingSections.forEachIndexed { index, section ->
            section.title?.let { title ->
                item("section.$index") {
                    WearSectionHeader(
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        title = stringResource(title),
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }
            section.items.forEachIndexed { itemIndex, item ->
                item("section.$index.item.$itemIndex") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        SmallOnboardingCard(
                            modifier = Modifier
                                .fillMaxWidth(),
                            contentModifier = Modifier
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(0.dp),
                            title = stringResource(item.title),
                            text = stringResource(item.text),
                            premium = item.premium,
                            imageVector = item.icon,
                        )
                    }
                }
            }
        }
    }
}
