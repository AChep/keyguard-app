@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.DataArray
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.OfflineBolt
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.PutOnboardingLastVisitInstant
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.grid.SimpleGridLayout
import com.artemchep.keyguard.ui.icons.KeyguardPremium
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.horizontalPaddingHalf
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.util.DividerColor
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.GlobalScope
import kotlinx.datetime.Clock
import org.kodein.di.compose.rememberInstance

val onboardingItemsPremium = listOf(
    OnboardingItem(
        title = Res.strings.feat_item_multiple_accounts_title,
        text = Res.strings.feat_item_multiple_accounts_text,
        premium = true,
        icon = Icons.Outlined.AccountBox,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_two_way_sync_title,
        text = Res.strings.feat_item_two_way_sync_text,
        premium = true,
        icon = Icons.Outlined.Sync,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_offline_editing_title,
        text = Res.strings.feat_item_offline_editing_text,
        premium = true,
        icon = Icons.Outlined.OfflineBolt,
    ),
)

val onboardingItemsSearch = listOf(
    OnboardingItem(
        title = Res.strings.feat_item_search_by_anything_title,
        text = Res.strings.feat_item_search_by_anything_text,
        icon = Icons.Outlined.Search,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_filter_title,
        text = Res.strings.feat_item_filter_text,
        icon = Icons.Outlined.FilterAlt,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_multiple_keywords_title,
        text = Res.strings.feat_item_multiple_keywords_text,
    ),
)

val onboardingItemsWatchtower = listOf(
    OnboardingItem(
        title = Res.strings.feat_item_pwned_passwords_title,
        text = Res.strings.feat_item_pwned_passwords_text,
        icon = Icons.Outlined.DataArray,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_password_strength_title,
        text = Res.strings.feat_item_password_strength_text,
        icon = Icons.Outlined.Password,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_reused_passwords_title,
        text = Res.strings.feat_item_reused_passwords_text,
        icon = Icons.Outlined.Recycling,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_inactive_totp_title,
        text = Res.strings.feat_item_inactive_totp_text,
        icon = Icons.Outlined.KeyguardTwoFa,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_unsecure_websites_title,
        text = Res.strings.feat_item_unsecure_websites_text,
        icon = Icons.Outlined.KeyguardWebsite,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_incomplete_items_title,
        text = Res.strings.feat_item_incomplete_items_text,
        icon = Icons.Outlined.ShortText,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_expiring_items_title,
        text = Res.strings.feat_item_expiring_items_text,
        icon = Icons.Outlined.Timer,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_duplicate_items_title,
        text = Res.strings.feat_item_duplicate_items_text,
        icon = Icons.Outlined.CopyAll,
    ),
)

val onboardingItemsOther = listOf(
    OnboardingItem(
        title = Res.strings.feat_item_multi_selection_title,
        text = Res.strings.feat_item_multi_selection_text,
        icon = Icons.Outlined.SelectAll,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_show_barcode_title,
        text = Res.strings.feat_item_show_barcode_text,
        icon = Icons.Outlined.QrCode,
    ),
    OnboardingItem(
        title = Res.strings.feat_item_generator_title,
        text = Res.strings.feat_item_generator_text,
    ),
)

@Composable
fun OnboardingScreen() {
    val putInstant by rememberInstance<PutOnboardingLastVisitInstant>()
    LaunchedEffect(putInstant) {
        putInstant(Clock.System.now())
            .attempt()
            .launchIn(GlobalScope)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.strings.feat_header_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        OnboardingScreenContent()
    }
}

@Composable
private fun ColumnScope.OnboardingScreenContent() {
    OnboardingContainer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPaddingHalf),
        items = onboardingItemsPremium,
    )
    Section(
        text = stringResource(Res.strings.feat_section_search_title),
    )
    OnboardingContainer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPaddingHalf),
        items = onboardingItemsSearch,
    )
    Section(
        text = stringResource(Res.strings.feat_section_watchtower_title),
    )
    OnboardingContainer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPaddingHalf),
        items = onboardingItemsWatchtower,
    )
    Section(
        text = stringResource(Res.strings.feat_section_misc_title),
    )
    OnboardingContainer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPaddingHalf),
        items = onboardingItemsOther,
    )
}

@Composable
private fun OnboardingContainer(
    modifier: Modifier = Modifier,
    items: List<OnboardingItem>,
) {
    SimpleGridLayout(
        modifier = modifier,
    ) {
        items.forEach { item ->
            OnboardingCard(
                modifier = Modifier,
                title = stringResource(item.title),
                text = stringResource(item.text),
                premium = item.premium,
                imageVector = item.icon,
            )
        }
    }
}

@Composable
fun OnboardingCard(
    modifier: Modifier = Modifier,
    title: String,
    text: String? = null,
    premium: Boolean = false,
    imageVector: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier,
            contentAlignment = Alignment.TopEnd,
        ) {
            if (imageVector != null) {
                Icon(
                    imageVector,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(72.dp)
                        .alpha(0.035f)
                        .align(Alignment.TopEnd),
                    contentDescription = null,
                )
            }
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                        .copy(
                            hyphens = Hyphens.Auto,
                            lineBreak = LineBreak.Heading,
                        ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                ExpandedIfNotEmpty(valueOrNull = text) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium
                            .copy(
                                hyphens = Hyphens.Auto,
                                lineBreak = LineBreak.Paragraph,
                            ),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ExpandedIfNotEmpty(
                    valueOrNull = Unit.takeIf { premium },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            modifier = Modifier
                                .size(12.dp),
                            imageVector = Icons.Outlined.KeyguardPremium,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(Res.strings.feat_keyguard_premium_label),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SmallOnboardingCard(
    modifier: Modifier = Modifier,
    title: String,
    text: String? = null,
    imageVector: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 0.dp,
        border = BorderStroke(Dp.Hairline, DividerColor),
    ) {
        Box(
            modifier = Modifier,
            contentAlignment = Alignment.TopEnd,
        ) {
            if (imageVector != null) {
                Icon(
                    imageVector,
                    modifier = Modifier
                        .padding(8.dp)
                        .size(72.dp)
                        .alpha(0.035f)
                        .align(Alignment.TopEnd),
                    contentDescription = null,
                )
            }
            Column(
                modifier = Modifier
                    .padding(8.dp)
                    .widthIn(max = 128.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall
                        .copy(
                            hyphens = Hyphens.Auto,
                            lineBreak = LineBreak.Heading,
                        ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ExpandedIfNotEmpty(valueOrNull = text) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall
                            .copy(
                                hyphens = Hyphens.Auto,
                                lineBreak = LineBreak.Paragraph,
                            ),
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
