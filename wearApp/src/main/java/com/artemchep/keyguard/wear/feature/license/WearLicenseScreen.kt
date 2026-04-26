package com.artemchep.keyguard.wear.feature.license

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.license.LicenseItemModel
import com.artemchep.keyguard.feature.license.produceLicenseState
import com.artemchep.keyguard.feature.license.toLicenseItemModel
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatTextFieldBadge
import com.artemchep.keyguard.ui.theme.infoContainer
import com.artemchep.keyguard.wear.ui.WearListEmpty
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.skeletonItems
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearLicenseScreen() {
    val loadableState = produceLicenseState()

    WearScaffoldScreen(
        title = stringResource(Res.string.settings_open_source_licenses_header_title),
    ) { transformationSpec ->
        when (loadableState) {
            is Loadable.Ok -> {
                val list = loadableState.value.content.items
                if (list.isEmpty()) {
                    item("empty") {
                        WearListEmpty(
                            modifier = Modifier
                                .transformedHeight(this, transformationSpec),
                            text = stringResource(Res.string.items_empty_label),
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }

                list.forEachIndexed { index, item ->
                    item("license.$index") {
                        WearLicenseItem(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            item = item.toLicenseItemModel(),
                            transformation = SurfaceTransformation(transformationSpec),
                        )
                    }
                }
            }

            is Loadable.Loading -> {
                skeletonItems(
                    transformationSpec = transformationSpec,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WearLicenseItem(
    modifier: Modifier = Modifier,
    item: LicenseItemModel,
    transformation: SurfaceTransformation? = null,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val scmUrl by rememberUpdatedState(item.scmUrl)

    Card(
        modifier = modifier,
        onClick = {
            val url = scmUrl ?: return@Card
            navigationController.queue(
                NavigationIntent.NavigateToBrowser(url = url),
            )
        },
        enabled = scmUrl != null,
        transformation = transformation,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = item.name ?: stringResource(Res.string.empty_value),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.dependency,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
            if (item.licenseNames.isNotEmpty()) {
                Spacer(
                    modifier = Modifier
                        .height(2.dp),
                )
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.licenseNames.forEach { licenseName ->
                        FlatTextFieldBadge(
                            backgroundColor = MaterialTheme.colorScheme.infoContainer,
                            text = licenseName,
                        )
                    }
                }
            }
        }
    }
}
