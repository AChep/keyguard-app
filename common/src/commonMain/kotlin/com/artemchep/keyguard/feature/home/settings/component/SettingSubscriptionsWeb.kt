package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_premium_membership_active_title
import com.artemchep.keyguard.res.pref_item_premium_membership_included_note
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource

/**
 * Premium is sold on the website, so there are no
 * store products to list; show the premium status.
 */
fun settingSubscriptionsWebProvider(
    getPurchased: GetPurchased,
): SettingComponent = getPurchased()
    .map { premium ->
        SettingIi(
            search = SettingIi.Search(
                group = "subscription",
                tokens = listOf(
                    "subscription",
                    "premium",
                ),
            ),
        ) {
            SettingSubscriptionsWeb(
                premium = premium,
            )
        }
    }

@Composable
private fun SettingSubscriptionsWeb(
    premium: Premium,
) {
    LocalSettingPaneComponents.current.KgBlock {
        SettingSubscriptionsHeader()
        when (premium) {
            Premium.PURCHASED -> FlatSimpleNote(
                text = stringResource(Res.string.pref_item_premium_membership_active_title),
                type = SimpleNote.Type.OK,
            )

            Premium.INCLUDED -> {
                Icon(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
                )
                Spacer(
                    modifier = Modifier
                        .height(16.dp),
                )
                Text(
                    modifier = Modifier
                        .padding(horizontal = Dimens.textHorizontalPadding),
                    text = stringResource(Res.string.pref_item_premium_membership_included_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
                )
            }

            Premium.NONE -> {
                // Do nothing
            }
        }
    }
}
