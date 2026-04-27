package com.artemchep.keyguard.wear.feature.datasafety

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.datasafety.DataSafetyItem
import com.artemchep.keyguard.feature.datasafety.dataSafetyItems
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.datasafety_header_title
import com.artemchep.keyguard.res.learn_more
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.WearDotsDivider
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.surfaceTransformation
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearDataSafetyScreen() {
    val items = dataSafetyItems()
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    WearScaffoldScreen(
        title = stringResource(Res.string.datasafety_header_title),
    ) { transformationSpec ->
        items.forEach { dataSafetyItem ->
            if (
                dataSafetyItem is DataSafetyItem.Divider ||
                dataSafetyItem is DataSafetyItem.Spacer
            ) {
                return@forEach
            }

            item(dataSafetyItem.key) {
                WearDataSafetyItem(
                    item = dataSafetyItem,
                    transformationSpec = transformationSpec,
                    onLearnMoreClick = { url ->
                        val intent = NavigationIntent.NavigateToBrowser(
                            url = url,
                        )
                        navigationController.queue(intent)
                    },
                )
            }
        }
    }
}

@Composable
private fun TransformingLazyColumnItemScope.WearDataSafetyItem(
    item: DataSafetyItem,
    transformationSpec: TransformationSpec,
    onLearnMoreClick: (String) -> Unit,
) {
    val surfaceTransformation = SurfaceTransformation(transformationSpec)
    when (item) {
        is DataSafetyItem.Divider -> {
            WearDotsDivider(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                transformation = surfaceTransformation,
            )
        }

        is DataSafetyItem.LargeSection -> {
            WearSectionHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                title = item.text,
                transformation = surfaceTransformation,
            )
        }

        is DataSafetyItem.LearnMore -> {
            WearListAction(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                title = stringResource(Res.string.learn_more),
                onClick = {
                    onLearnMoreClick(item.url)
                },
                transformation = surfaceTransformation,
            )
        }

        is DataSafetyItem.Row -> {
            WearDataSafetyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                title = item.title,
                value = item.value,
                secondary = item.secondary,
                transformation = surfaceTransformation,
            )
        }

        is DataSafetyItem.Section -> {
            WearSectionHeader(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                title = item.text,
                transformation = surfaceTransformation,
            )
        }

        is DataSafetyItem.Spacer -> {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(item.height)
                    .transformedHeight(this, transformationSpec)
                    .surfaceTransformation(surfaceTransformation),
            )
        }

        is DataSafetyItem.Text -> {
            WearDataSafetyText(
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, transformationSpec),
                text = item.text,
                secondary = item.secondary,
                transformation = surfaceTransformation,
            )
        }
    }
}

@Composable
private fun WearDataSafetyRow(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    secondary: Boolean,
    transformation: SurfaceTransformation? = null,
) {
    WearListCard(
        modifier = modifier
            .fillMaxWidth(),
        title = {
            Text(
                text = title,
            )
        },
        text = {
            Text(
                text = value,
            )
        },
        transformation = transformation,
    )
}

@Composable
private fun WearDataSafetyText(
    modifier: Modifier = Modifier,
    text: String,
    secondary: Boolean,
    transformation: SurfaceTransformation? = null,
) {
    if (secondary) {
        WearListLabel(
            modifier = modifier,
            textAlign = TextAlign.Start,
            text = text,
            transformation = transformation,
        )
        return
    }

    Card(
        modifier = modifier
            .heightIn(min = 16.dp),
        colors = CardDefaults.cardColors().run {
            copy(
                containerColor = Color.Transparent,
            )
        },
        transformation = transformation,
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = text,
            textAlign = TextAlign.Start,
        )
    }
}
