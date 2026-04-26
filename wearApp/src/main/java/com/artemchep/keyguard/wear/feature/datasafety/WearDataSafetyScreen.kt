package com.artemchep.keyguard.wear.feature.datasafety

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.Text
import com.artemchep.keyguard.feature.datasafety.DataSafetyScreenContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.datasafety_header_title
import com.artemchep.keyguard.wear.ui.ProxyMaterial3Styles
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearScaffoldColumn
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearDataSafetyScreen() {
    WearScaffoldColumn(
        title = stringResource(Res.string.datasafety_header_title),
    ) {
        DataSafetyScreenContent(
            row = { modifier, title, value ->
                WearDataSafetyRow(
                    modifier = modifier,
                    title = title,
                    value = value,
                )
            },
        )
    }
}

@Composable
private fun WearDataSafetyRow(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
) {
    WearListCard(
        modifier = modifier
            .fillMaxWidth(),
        title = {
            ProxyMaterial3Styles {
                Text(
                    text = title,
                )
            }
        },
        text = {
            ProxyMaterial3Styles {
                Text(
                    text = value,
                )
            }
        },
    )
}
