package com.artemchep.keyguard.feature.applink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_how_pair_text
import com.artemchep.keyguard.res.app_link_how_pair_title
import com.artemchep.keyguard.res.app_link_how_privacy_text
import com.artemchep.keyguard.res.app_link_how_privacy_title
import com.artemchep.keyguard.res.app_link_how_security_text
import com.artemchep.keyguard.res.app_link_how_security_title
import com.artemchep.keyguard.res.app_link_how_sync_text
import com.artemchep.keyguard.res.app_link_how_sync_title
import com.artemchep.keyguard.res.app_link_relay_how_text
import com.artemchep.keyguard.res.app_link_relay_how_title
import com.artemchep.keyguard.res.app_link_relay_sees_text
import com.artemchep.keyguard.res.app_link_relay_sees_title
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How linked devices work, a title and a text each. */
private val appLinkHowItWorks: List<Pair<StringResource, StringResource>>
    get() = listOf(
        Res.string.app_link_how_pair_title to Res.string.app_link_how_pair_text,
        Res.string.app_link_how_sync_title to Res.string.app_link_how_sync_text,
        Res.string.app_link_how_privacy_title to Res.string.app_link_how_privacy_text,
        Res.string.app_link_how_security_title to Res.string.app_link_how_security_text,
    )

/** How the relay is used, and what it learns, a title and a text each. */
internal val appLinkRelayInfo: List<Pair<StringResource, StringResource>>
    get() = listOf(
        Res.string.app_link_relay_how_title to Res.string.app_link_relay_how_text,
        Res.string.app_link_relay_sees_title to Res.string.app_link_relay_sees_text,
    )

/** How linked devices work, as the info notes of the backups and feedback screens. */
@Composable
internal fun AppLinkHowItWorks() {
    AppLinkInfoSection(
        notes = appLinkHowItWorks,
    )
}

/** The [notes], a title and a text each, under an info icon. */
@Composable
internal fun AppLinkInfoSection(
    notes: List<Pair<StringResource, StringResource>>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
        Icon(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Column(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            notes.forEach { (title, text) ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                            .combineAlpha(MediumEmphasisAlpha),
                    )
                }
            }
        }
    }
}
