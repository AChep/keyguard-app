package com.artemchep.keyguard.feature.home.vault.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.onboarding.OnboardingScreenContent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_promo_features
import com.artemchep.keyguard.res.addaccount_promo_title
import com.artemchep.keyguard.ui.BetaBadge
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddAccountView(
    modifier: Modifier = Modifier,
    onClick: ((AccountType) -> Unit)?,
) {
    Column(
        modifier = modifier,
    ) {
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.textHorizontalPadding),
            text = stringResource(Res.string.addaccount_promo_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
        )
        Section(
            expressive = true,
        )

        val updatedOnClick by rememberUpdatedState(onClick)
        val entries = remember {
            AccountType.entries
        }
        entries.forEachIndexed { index, type ->
            val shapeState = getShapeState(
                list = entries,
                index = index,
                predicate = { _, _ -> true },
            )
            FlatItemSimpleExpressive(
                elevation = 1.dp,
                shapeState = shapeState,
                title = {
                    Text(
                        text = type.fullName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                text = {
                    Text(
                        text = stringResource(type.descriptionRes),
                    )
                },
                trailing = {
                    if (type.beta) {
                        BetaBadge()
                        Spacer(
                            modifier = Modifier
                                .width(16.dp),
                        )
                    }
                    ChevronIcon()
                },
                leading = {
                    Icon(
                        imageVector = type.iconImageVector,
                        contentDescription = null,
                    )
                },
                onClick = {
                    updatedOnClick?.invoke(type)
                },
                enabled = updatedOnClick != null,
            )
        }
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
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
            text = stringResource(Res.string.addaccount_promo_features),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        OnboardingScreenContent()
    }
}
