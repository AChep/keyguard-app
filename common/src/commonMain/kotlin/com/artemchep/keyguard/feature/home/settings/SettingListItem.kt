package com.artemchep.keyguard.feature.home.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.selectedContainer

@Composable
fun SettingListItem(
    item: SettingsItem,
    selected: Boolean,
    onClick: (SettingsItem) -> Unit,
) {
    Column {
        FlatItemLayout(
            backgroundColor = if (selected) MaterialTheme.colorScheme.selectedContainer else Color.Unspecified,
            leading = {
                Avatar {
                    when {
                        item.leading != null -> {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.Center),
                            ) {
                                item.leading.invoke(this)
                            }
                        }

                        item.icon != null -> {
                            Icon(
                                modifier = Modifier
                                    .align(Alignment.Center),
                                imageVector = item.icon,
                                contentDescription = null,
                            )
                        }

                        else -> {
                        }
                    }
                }
            },
            trailing = {
                if (item.trailing != null) {
                    item.trailing.invoke(this)
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                }
                ChevronIcon()
            },
            content = {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = textResource(item.title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    text = {
                        Text(
                            text = textResource(item.text),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            },
            onClick = {
                onClick(item)
            },
        )
        if (item.content != null) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 16.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 64.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item.content.invoke(this)
            }
        }
    }
}
