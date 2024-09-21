package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.usecase.GetProducts
import com.artemchep.keyguard.common.usecase.GetSubscriptions
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.surfaceColorAtElevationSemi
import com.artemchep.keyguard.feature.onboarding.OnboardingCard
import com.artemchep.keyguard.feature.onboarding.onboardingItemsPremium
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultEmphasisAlpha
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.FlatTextFieldBadge
import com.artemchep.keyguard.ui.GridLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val SubscriptionsCountDefault = 2
private const val ProductsCountDefault = 1

fun settingSubscriptionsProvider(
    directDI: DirectDI,
) = settingSubscriptionsProvider(
    getSubscriptions = directDI.instance(),
    getProducts = directDI.instance(),
)

fun settingSubscriptionsProvider(
    getSubscriptions: GetSubscriptions,
    getProducts: GetProducts,
): SettingComponent = combine(
    getSubscriptions()
        .loadable(),
    getProducts()
        .loadable(),
) { loadableSubscriptions, loadableProducts ->
    SettingIi(
        search = SettingIi.Search(
            group = "subscription",
            tokens = listOf(
                "subscription",
                "premium",
            ),
        ),
    ) {
        SettingSubscriptions(
            loadableSubscriptions = loadableSubscriptions,
            loadableProducts = loadableProducts,
        )
    }
}

fun <T> Flow<T>.loadable(): Flow<Loadable<T>> = this
    .map {
        val state: Loadable<T> = Loadable.Ok(it)
        state
    }
    .onStart {
        val initialState = Loadable.Loading
        emit(initialState)
    }

@Composable
private fun SettingSubscriptions(
    loadableSubscriptions: Loadable<List<Subscription>?>,
    loadableProducts: Loadable<List<Product>?>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Text(
            stringResource(Res.string.pref_item_premium_membership_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
        )
        Text(
            stringResource(Res.string.pref_item_premium_membership_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(top = 4.dp)
                .padding(horizontal = Dimens.horizontalPadding),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .padding(top = 8.dp, bottom = 16.dp)
                .horizontalScroll(rememberScrollState())
                .padding(start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            onboardingItemsPremium.forEach { item ->
                OnboardingCard(
                    modifier = Modifier
                        .widthIn(max = 156.dp),
                    title = stringResource(item.title),
                    text = stringResource(item.text),
                    imageVector = item.icon,
                )
            }
        }
        Section(text = stringResource(Res.string.pref_item_premium_membership_section_subscriptions_title))
        loadableSubscriptions.fold(
            ifLoading = {
                SettingGroupLayout {
                    repeat(SubscriptionsCountDefault) {
                        SettingSubscriptionSkeletonItem(
                            modifier = Modifier,
                        )
                    }
                }
            },
            ifOk = { subscriptions ->
                if (subscriptions == null) {
                    FlatSimpleNote(
                        text = stringResource(Res.string.pref_item_premium_membership_failed_to_load_subscriptions),
                        type = SimpleNote.Type.WARNING,
                    )
                    return@fold
                }
                SettingGroupLayout {
                    subscriptions.forEach { subscription ->
                        SettingSubscriptionItem(
                            modifier = Modifier,
                            subscription = subscription,
                        )
                    }
                }
            },
        )
        Text(
            stringResource(Res.string.pref_item_premium_membership_section_subscriptions_note),
            modifier = Modifier
                .padding(top = 16.dp)
                .padding(horizontal = Dimens.horizontalPadding),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            fontSize = 12.sp,
        )
        Section(text = stringResource(Res.string.pref_item_premium_membership_section_products_title))
        loadableProducts.fold(
            ifLoading = {
                SettingGroupLayout {
                    repeat(ProductsCountDefault) {
                        SettingSubscriptionSkeletonItem()
                    }
                }
            },
            ifOk = { products ->
                if (products == null) {
                    FlatSimpleNote(
                        text = stringResource(Res.string.pref_item_premium_membership_failed_to_load_products),
                        type = SimpleNote.Type.WARNING,
                    )
                    return@fold
                }
                SettingGroupLayout {
                    products.forEach { product ->
                        SettingProductItem(
                            product = product,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun SettingSubscriptionSkeletonItem(
    modifier: Modifier = Modifier,
) {
    SettingItemLayout(
        modifier = modifier,
        isActive = false,
        onClick = {
            // Do nothing
        },
        title = {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.45f),
                style = MaterialTheme.typography.titleSmall,
            )
        },
        price = {
            SkeletonText(
                modifier = Modifier
                    .fillMaxWidth(0.45f),
                style = MaterialTheme.typography.titleMedium,
            )
        },
    )
}

@Composable
private fun SettingSubscriptionItem(
    modifier: Modifier = Modifier,
    subscription: Subscription,
) {
    val context by rememberUpdatedState(LocalLeContext)
    val status = subscription.status
    SettingItemLayout(
        modifier = modifier,
        isActive = status is Subscription.Status.Active,
        onClick = {
            subscription.purchase(context)
        },
        title = {
            Text(subscription.title)
        },
        price = {
            val text = "${subscription.price} / ${subscription.periodFormatted}"
            Text(text)
        },
        content = if (status is Subscription.Status.Inactive && status.trialPeriodFormatted != null) {
            // composable
            {
                FlatTextFieldBadge(
                    type = TextFieldModel2.Vl.Type.INFO,
                    text = stringResource(
                        Res.string.pref_item_premium_status_free_trial_n,
                        status.trialPeriodFormatted,
                    ),
                )
            }
        } else {
            null
        },
    )
}

@Composable
private fun SettingProductItem(
    modifier: Modifier = Modifier,
    product: Product,
) {
    val context by rememberUpdatedState(LocalLeContext)
    val status = product.status
    SettingItemLayout(
        modifier = modifier,
        isActive = status is Product.Status.Active,
        onClick = {
            product.purchase(context)
        },
        title = {
            Text(product.title)
        },
        price = {
            Text(product.price)
        },
    )
}

@Composable
private fun SettingGroupLayout(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GridLayout(
        modifier = modifier
            .padding(horizontal = 8.dp),
        columns = 2,
        mainAxisSpacing = 8.dp,
        crossAxisSpacing = 8.dp,
    ) {
        content()
    }
}

@Composable
private fun SettingItemLayout(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    onClick: () -> Unit,
    title: @Composable ColumnScope.() -> Unit,
    price: @Composable ColumnScope.() -> Unit,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val backgroundModifier = run {
        val tintColor = MaterialTheme.colorScheme
            .surfaceColorAtElevationSemi(1.dp)
        Modifier
            .background(tintColor)
    }
    val borderModifier = if (isActive) {
        Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium)
    } else Modifier
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .then(backgroundModifier)
            .then(borderModifier),
        propagateMinConstraints = true,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.TopEnd,
        ) {
            Icon(
                Icons.Outlined.Star,
                modifier = Modifier
                    .size(128.dp)
                    .alpha(0.035f),
                contentDescription = null,
            )
        }
        val updatedOnClick by rememberUpdatedState(onClick)
        Column(
            modifier = Modifier
                .clickable(role = Role.Button) {
                    updatedOnClick()
                }
                .padding(8.dp),
        ) {
            val localEmphasis = DefaultEmphasisAlpha
            val localTitleTextStyle = TextStyle(
                color = LocalContentColor.current
                    .combineAlpha(localEmphasis)
                    .combineAlpha(MediumEmphasisAlpha),
            )
            val localPriceTextStyle = TextStyle(
                color = LocalContentColor.current
                    .combineAlpha(localEmphasis),
            )
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.titleSmall
                    .merge(localTitleTextStyle),
            ) {
                title()
            }
            Spacer(modifier = Modifier.height(8.dp))
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.titleMedium
                    .merge(localPriceTextStyle),
            ) {
                price()
            }
            if (content != null) {
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }
}
