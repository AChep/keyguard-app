package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha

internal fun LazyListScope.automaticBackupsSettingsHeader(
    key: String,
    title: String,
) {
    item("$key.title") {
        Text(
            modifier = Modifier
                .animateItem()
                .padding(
                    start = Dimens.textHorizontalPadding,
                    end = Dimens.textHorizontalPadding,
                    top = 24.dp,
                    bottom = 8.dp,
                ),
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.combineAlpha(MediumEmphasisAlpha),
        )
    }
}
