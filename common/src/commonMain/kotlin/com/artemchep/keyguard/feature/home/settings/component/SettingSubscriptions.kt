package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.Product
import com.artemchep.keyguard.common.model.Subscription
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.usecase.GetProducts
import com.artemchep.keyguard.common.usecase.GetSubscriptions
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.onboarding.OnboardingCard
import com.artemchep.keyguard.feature.onboarding.onboardingItemsPremium
import com.artemchep.keyguard.platform.LocalLeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.Ah
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.theme.Dimens
import dev.icerock.moko.resources.compose.stringResource
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
            stringResource(Res.strings.pref_item_premium_membership_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
        )
        Text(
            stringResource(Res.strings.pref_item_premium_membership_text),
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
        Section(text = stringResource(Res.strings.pref_item_premium_membership_section_subscriptions_title))
        loadableSubscriptions.fold(
            ifLoading = {
                repeat(SubscriptionsCountDefault) {
                    SettingSubscriptionSkeletonItem()
                }
            },
            ifOk = { subscriptions ->
                if (subscriptions == null) {
                    FlatSimpleNote(
                        text = stringResource(Res.strings.pref_item_premium_membership_failed_to_load_subscriptions),
                        type = SimpleNote.Type.WARNING,
                    )
                    return@fold
                }
                subscriptions.forEach { subscription ->
                    SettingSubscriptionItem(
                        subscription = subscription,
                    )
                }
            },
        )
        Section(text = stringResource(Res.strings.pref_item_premium_membership_section_products_title))
        loadableProducts.fold(
            ifLoading = {
                repeat(ProductsCountDefault) {
                    SettingSubscriptionSkeletonItem()
                }
            },
            ifOk = { products ->
                if (products == null) {
                    FlatSimpleNote(
                        text = stringResource(Res.strings.pref_item_premium_membership_failed_to_load_products),
                        type = SimpleNote.Type.WARNING,
                    )
                    return@fold
                }
                products.forEach { product ->
                    SettingProductItem(
                        product = product,
                    )
                }
            },
        )
    }
}

@Composable
private fun SettingSubscriptionSkeletonItem() {
    val contentColor =
        LocalContentColor.current.copy(alpha = DisabledEmphasisAlpha)
    FlatItemLayout(
        modifier = Modifier
            .shimmer(),
        leading = {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(contentColor, CircleShape),
            )
        },
        content = {
            Box(
                Modifier
                    .height(18.dp)
                    .fillMaxWidth(0.45f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor),
            )
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .height(15.dp)
                    .fillMaxWidth(0.35f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor.copy(alpha = 0.2f)),
            )
        },
    )
}

@Composable
private fun SettingSubscriptionItem(
    subscription: Subscription,
) {
    val context by rememberUpdatedState(LocalLeContext)
    FlatItemLayout(
        leading = {
            val backgroundColor = MaterialTheme.colorScheme.secondaryContainer
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(backgroundColor, CircleShape)
                    .padding(4.dp),
            ) {
                val targetContentColor = kotlin.run {
                    val active = subscription.status is Subscription.Status.Active
                    if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        contentColorFor(backgroundColor)
                    }
                }
                val contentColor by animateColorAsState(targetValue = targetContentColor)
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        },
        elevation = 2.dp,
        trailing = {
            ChevronIcon()
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(subscription.price)
                },
                text = {
                    Text(subscription.title)
                },
            )

            val statusOrNull = subscription.status as? Subscription.Status.Active
            ExpandedIfNotEmpty(statusOrNull) { status ->
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp),
                ) {
                    Ah(
                        score = 1f,
                        text = stringResource(Res.strings.pref_item_premium_status_active),
                    )

                    val isCancelled = !status.willRenew
                    AnimatedVisibility(
                        modifier = Modifier
                            .padding(start = 4.dp),
                        visible = isCancelled,
                    ) {
                        Ah(
                            score = 0f,
                            text = stringResource(Res.strings.pref_item_premium_status_will_not_renew),
                        )
                    }
                }
            }
        },
        onClick = {
            subscription.purchase(context)
        },
    )
}

@Composable
private fun SettingProductItem(
    product: Product,
) {
    val context by rememberUpdatedState(LocalLeContext)
    FlatItemLayout(
        leading = {
            val backgroundColor = MaterialTheme.colorScheme.secondaryContainer
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(backgroundColor, CircleShape)
                    .padding(4.dp),
            ) {
                val targetContentColor = kotlin.run {
                    val active = product.status is Product.Status.Active
                    if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        contentColorFor(backgroundColor)
                    }
                }
                val contentColor by animateColorAsState(targetValue = targetContentColor)
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        },
        elevation = 2.dp,
        trailing = {
            ChevronIcon()
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(product.price)
                },
                text = {
                    Text(product.title)
                },
            )

            val statusOrNull = product.status as? Product.Status.Active
            ExpandedIfNotEmpty(statusOrNull) {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp),
                ) {
                    Ah(
                        score = 1f,
                        text = stringResource(Res.strings.pref_item_premium_status_active),
                    )
                }
            }
        },
        onClick = {
            product.purchase(context)
        },
    )
}
