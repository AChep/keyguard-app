package com.artemchep.keyguard.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.favicon.PictureUrl

@Composable
fun UserIcon(
    modifier: Modifier = Modifier,
    pictureUrl: PictureUrl?,
) {
    AsyncIcon(
        imageModel = { pictureUrl },
        modifier = modifier,
        contentDescription = "Person",
        errorImageVector = Icons.Outlined.Person,
    )
}
