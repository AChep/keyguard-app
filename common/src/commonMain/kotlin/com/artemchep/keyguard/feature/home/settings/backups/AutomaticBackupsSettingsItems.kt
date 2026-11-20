package com.artemchep.keyguard.feature.home.settings.backups

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha

/**
 * Collects the keyed items of the Automatic backups list, so the
 * screen knows their keys and wraps each one in a focus target.
 */
internal class AutomaticBackupsListScope {
    class Item(
        val key: String,
        val content: @Composable () -> Unit,
    )

    private val items = mutableListOf<Item>()

    fun item(
        key: String,
        content: @Composable () -> Unit,
    ) {
        items += Item(key, content)
    }

    fun build(): List<Item> = items.toList()
}

internal fun automaticBackupsListItems(
    block: AutomaticBackupsListScope.() -> Unit,
): List<AutomaticBackupsListScope.Item> = AutomaticBackupsListScope()
    .apply(block)
    .build()

internal fun AutomaticBackupsListScope.automaticBackupsSettingsHeader(
    key: String,
    title: String,
) {
    item("$key.title") {
        Text(
            modifier = Modifier
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
