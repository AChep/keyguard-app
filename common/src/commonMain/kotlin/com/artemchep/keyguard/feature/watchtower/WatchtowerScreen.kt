package com.artemchep.keyguard.feature.watchtower

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.material.icons.outlined.DataArray
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Recycling
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.formatLocalized
import com.artemchep.keyguard.feature.appreview.RequestAppReviewEffect
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.model.FilterItem
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.search.filter.FilterButton
import com.artemchep.keyguard.feature.search.filter.FilterScreen
import com.artemchep.keyguard.feature.twopane.TwoPaneScreen
import com.artemchep.keyguard.platform.leIme
import com.artemchep.keyguard.platform.leNavigationBars
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.Ah
import com.artemchep.keyguard.ui.DefaultEmphasisAlpha
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.GridLayout
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.animatedNumberText
import com.artemchep.keyguard.ui.grid.preferredGridWidth
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.poweredby.PoweredBy2factorauth
import com.artemchep.keyguard.ui.poweredby.PoweredByHaveibeenpwned
import com.artemchep.keyguard.ui.poweredby.PoweredByPasskeys
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonItemPilled
import com.artemchep.keyguard.ui.skeleton.SkeletonSection
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

@Composable
fun WatchtowerScreen() {
    RequestAppReviewEffect()

    val state = produceWatchtowerState()
    WatchtowerScreen(
        state = state,
    )
}

@Composable
fun WatchtowerScreen(
    state: WatchtowerState,
) {
    TwoPaneScreen(
        detail = { modifier ->
            VaultHomeScreenFilterPaneCard(
                modifier = modifier,
                state = state,
            )
        },
    ) { modifier, detailIsVisible ->
        WatchtowerScreen2(
            modifier = modifier,
            state = state,
            showFilter = !detailIsVisible,
        )
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
private fun VaultHomeScreenFilterPaneCard(
    modifier: Modifier = Modifier,
    state: WatchtowerState,
) {
    VaultHomeScreenFilterPaneCard2(
        modifier = modifier,
        items = state.filter.items,
        onClear = state.filter.onClear,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)
@Composable
fun VaultHomeScreenFilterPaneCard2(
    modifier: Modifier = Modifier,
    items: List<FilterItem>,
    onClear: (() -> Unit)?,
) {
    FilterScreen(
        modifier = modifier,
        count = null,
        items = items,
        onClear = onClear,
        actions = {
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchtowerScreen2(
    modifier: Modifier,
    state: WatchtowerState,
    showFilter: Boolean,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.strings.watchtower_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    if (showFilter) {
                        VaultHomeScreenFilterButton(
                            state = state,
                        )
                    }
                    OptionsButton(
                        actions = state.actions,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        ContentLayout(
            contentPadding = contentPadding,
            dashboardContent = {
                val passwordStrength by remember(state.content) {
                    if (state.content is Loadable.Ok) {
                        state.content.value.strength
                    } else {
                        MutableStateFlow(Loadable.Loading)
                    }
                }.collectAsState()
                DashboardContent(passwordStrength)
            },
            cardsContent = {
                state.content.RenderCard(
                    transform = { pwned },
                ) { pwnedPasswords ->
                    CardPwnedPassword(pwnedPasswords)
                }

                state.content.RenderCard(
                    transform = { pwnedWebsites },
                ) { pwnedWebsites ->
                    CardVulnerableAccounts(pwnedWebsites)
                }

                state.content.RenderCard(
                    transform = { reused },
                ) { reusedPasswords ->
                    CardReusedPassword(reusedPasswords)
                }

                state.content.RenderCard(
                    transform = { inactiveTwoFactorAuth },
                ) { inactiveTwoFactorAuth ->
                    CardInactiveTwoFactorAuth(inactiveTwoFactorAuth)
                }

                state.content.RenderCard(
                    transform = { unsecureWebsites },
                ) { unsecureWebsites ->
                    CardUnsecureWebsites(unsecureWebsites)
                }

                // TODO:
                /*
                state.content.RenderCard(
                    transform = { accountCompromised },
                ) { accountCompromised ->
                    CardCompromisedAccount(accountCompromised)
                }
                 */

                state.content.RenderCard(
                    transform = { inactivePasskey },
                ) { inactivePasskey ->
                    CardInactivePasskey(inactivePasskey)
                }
            },
            cardsContent2 = {
                state.content.RenderCard(
                    transform = { duplicateItems },
                ) { duplicateItems ->
                    CardDuplicateItems(duplicateItems)
                }

                state.content.RenderCard(
                    transform = { incompleteItems },
                ) { incompleteItems ->
                    CardIncompleteItems(incompleteItems)
                }

                state.content.RenderCard(
                    transform = { expiringItems },
                ) { expiringItems ->
                    CardExpiringItems(expiringItems)
                }

                state.content.RenderCard(
                    transform = { duplicateWebsites },
                ) { duplicateWebsites ->
                    CardDuplicateWebsites(duplicateWebsites)
                }

                state.content.RenderCard(
                    transform = { trashedItems },
                ) { trashedItems ->
                    CardTrashedItems(trashedItems)
                }

                state.content.RenderCard(
                    transform = { emptyItems },
                ) { emptyItems ->
                    CardEmptyItems(emptyItems)
                }
            },
            loaded = remember {
                derivedStateOf {
//                    val content = state.content.getOrNull()
//                        ?: return@derivedStateOf false
//                    combine(
//                        content.duplicateItems,
//                    )
                    false
                }
            },
        )
    }
}

@Composable
private inline fun <T, R> Loadable<T>.RenderCard(
    crossinline transform: T.() -> StateFlow<Loadable<R?>>,
    content: @Composable (R) -> Unit,
) {
    val valueOrException by remember(this) {
        if (this is Loadable.Ok) {
            this.value.let(transform)
        } else {
            MutableStateFlow(Loadable.Loading)
        }
    }.collectAsState()
    valueOrException.fold(
        ifLoading = {
            CardSkeleton()
        },
        ifOk = { data ->
            if (data != null) content(data)
        },
    )
}

@Composable
private fun VaultHomeScreenFilterButton(
    modifier: Modifier = Modifier,
    state: WatchtowerState,
) {
    VaultHomeScreenFilterButton2(
        modifier = modifier,
        items = state.filter.items,
        onClear = state.filter.onClear,
    )
}

@Composable
fun VaultHomeScreenFilterButton2(
    modifier: Modifier = Modifier,
    items: List<FilterItem>,
    onClear: (() -> Unit)?,
) {
    FilterButton(
        modifier = modifier,
        count = null,
        items = items,
        onClear = onClear,
    )
}

// Dashboard

@Composable
private fun ColumnScope.DashboardContent(
    content: Loadable<WatchtowerState.Content.PasswordStrength?>,
) = content.fold(
    ifOk = { passwordStrengthOrNull ->
        ExpandedIfNotEmpty(
            valueOrNull = passwordStrengthOrNull,
        ) { passwordStrength ->
            Column {
                DashboardContentData(passwordStrength)
            }
        }
    },
    ifLoading = {
        DashboardContentSkeleton()
    },
)

@Composable
private fun ColumnScope.DashboardContentData(
    content: WatchtowerState.Content.PasswordStrength,
) {
    Section(
        text = stringResource(Res.strings.watchtower_section_password_strength_label),
    )
    content.items.forEach { (t, u, onClick) ->
        key(t) {
            val score = when (t) {
                PasswordStrength.Score.Weak -> 0f
                PasswordStrength.Score.Fair -> 0.2f
                PasswordStrength.Score.Good -> 0.5f
                PasswordStrength.Score.Strong -> 0.9f
                PasswordStrength.Score.VeryStrong -> 1f
            }
            FlatItem(
                title = {
                    val text = t.formatLocalized()
                    Text(text)
                },
                leading = {
                    val numberStr = animatedNumberText(u)
                    Ah(
                        score = score,
                        text = numberStr,
                    )
                },
                trailing = {
                    ChevronIcon()
                },
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun ColumnScope.DashboardContentSkeleton() {
    SkeletonSection()
    for (i in 0..4)
        SkeletonItemPilled()
}

// Cards

@Composable
private fun CardPwnedPassword(
    state: WatchtowerState.Content.PwnedPasswords,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.ERROR.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_pwned_passwords_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_pwned_passwords_text),
        imageVector = Icons.Outlined.DataArray,
        onClick = state.onClick,
        content = {
            PoweredByHaveibeenpwned(
                modifier = Modifier
                    .fillMaxWidth(),
                fill = true,
            )
        },
    )
}

@Composable
private fun CardReusedPassword(
    state: WatchtowerState.Content.ReusedPasswords,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.ERROR.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_reused_passwords_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_reused_passwords_text),
        imageVector = Icons.Outlined.Recycling,
        onClick = state.onClick,
    )
}

@Composable
private fun CardVulnerableAccounts(
    state: WatchtowerState.Content.PwnedWebsites,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.ERROR.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_vulnerable_accounts_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_vulnerable_accounts_text),
        imageVector = Icons.Outlined.KeyguardWebsite,
        onClick = state.onClick,
        content = {
            PoweredByHaveibeenpwned(
                modifier = Modifier
                    .fillMaxWidth(),
                fill = true,
            )
        },
    )
}

@Composable
private fun CardUnsecureWebsites(
    state: WatchtowerState.Content.UnsecureWebsites,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.WARNING.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_unsecure_websites_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_unsecure_websites_text),
        imageVector = Icons.Outlined.KeyguardWebsite,
        onClick = state.onClick,
    )
}

@Composable
private fun CardCompromisedAccount(
    state: WatchtowerState.Content.CompromisedAccounts,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.WARNING.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_compromised_accounts_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_compromised_accounts_text),
        imageVector = Icons.Outlined.AccountTree,
        onClick = state.onClick,
        content = {
            PoweredByHaveibeenpwned(
                modifier = Modifier
                    .fillMaxWidth(),
                fill = true,
            )
        },
    )
}

@Composable
private fun CardInactiveTwoFactorAuth(
    state: WatchtowerState.Content.InactiveTwoFactorAuth,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.WARNING.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_inactive_2fa_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_inactive_2fa_text),
        imageVector = Icons.Outlined.KeyguardTwoFa,
        onClick = state.onClick,
        content = {
            PoweredBy2factorauth(
                modifier = Modifier
                    .fillMaxWidth(),
                fill = true,
            )
        },
    )
}

@Composable
private fun CardInactivePasskey(
    state: WatchtowerState.Content.InactivePasskey,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.INFO.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_inactive_passkey_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_inactive_passkey_text),
        imageVector = Icons.Outlined.KeyguardTwoFa,
        onClick = state.onClick,
        content = {
            PoweredByPasskeys(
                modifier = Modifier
                    .fillMaxWidth(),
                fill = true,
            )
        },
    )
}

@Composable
private fun CardIncompleteItems(
    state: WatchtowerState.Content.IncompleteItems,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.INFO.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_incomplete_items_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_incomplete_items_text),
        imageVector = Icons.Outlined.ShortText,
        onClick = state.onClick,
    )
}

@Composable
private fun CardExpiringItems(
    state: WatchtowerState.Content.ExpiringItems,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.INFO.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_expiring_items_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_expiring_items_text),
        imageVector = Icons.Outlined.Timer,
        onClick = state.onClick,
    )
}

@Composable
private fun CardDuplicateWebsites(
    state: WatchtowerState.Content.DuplicateWebsites,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.INFO.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_duplicate_websites_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_duplicate_websites_text),
        imageVector = Icons.Outlined.KeyguardWebsite,
        onClick = state.onClick,
    )
}

@Composable
private fun CardTrashedItems(
    state: WatchtowerState.Content.TrashedItems,
) {
    Card(
        number = state.count,
        title = {
            val icon = when {
                // See:
                // https://github.com/bitwarden/mobile/issues/1723
                // for a reasoning behind these numbers.
                state.count > 2000 -> WatchtowerStatusIcon.ERROR
                state.count > 500 -> WatchtowerStatusIcon.WARNING
                state.count > 0 -> WatchtowerStatusIcon.INFO
                else -> WatchtowerStatusIcon.OK
            }
            ContentCardsContentTitle(
                icon = icon,
                title = stringResource(Res.strings.watchtower_item_trashed_items_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_trashed_items_text),
        imageVector = Icons.Outlined.Delete,
        onClick = state.onClick,
    )
}

@Composable
private fun CardEmptyItems(
    state: WatchtowerState.Content.EmptyItems,
) {
    Card(
        number = state.count,
        title = {
            val icon = when {
                state.count > 50 -> WatchtowerStatusIcon.ERROR
                state.count > 5 -> WatchtowerStatusIcon.WARNING
                state.count > 0 -> WatchtowerStatusIcon.INFO
                else -> WatchtowerStatusIcon.OK
            }
            ContentCardsContentTitle(
                icon = icon,
                title = stringResource(Res.strings.watchtower_item_empty_folders_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_empty_folders_text),
        imageVector = Icons.Outlined.FolderOff,
        onClick = state.onClick,
    )
}

@Composable
private fun CardDuplicateItems(
    state: WatchtowerState.Content.DuplicateItems,
) {
    Card(
        number = state.count,
        title = {
            ContentCardsContentTitle(
                icon = WatchtowerStatusIcon.INFO.takeIf { state.count > 0 }
                    ?: WatchtowerStatusIcon.OK,
                title = stringResource(Res.strings.watchtower_item_duplicate_items_title),
            )
        },
        text = stringResource(Res.strings.watchtower_item_duplicate_items_text),
        imageVector = Icons.Outlined.CopyAll,
        onClick = state.onClick,
    )
}

private enum class WatchtowerStatusIcon {
    OK,
    INFO,
    WARNING,
    ERROR,
}

@Composable
private fun RowScope.ContentCardsContentTitle(
    icon: WatchtowerStatusIcon,
    title: String,
) {
    Text(
        modifier = Modifier
            .weight(1f),
        text = title,
    )
    Spacer(
        modifier = Modifier
            .width(8.dp),
    )
    Crossfade(icon) { ic ->
        when (ic) {
            WatchtowerStatusIcon.OK -> {
                Icon(
                    Icons.Outlined.CheckCircleOutline,
                    modifier = Modifier
                        .align(Alignment.CenterVertically),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.ok,
                )
            }

            WatchtowerStatusIcon.INFO -> {
                Icon(
                    Icons.Outlined.Info,
                    modifier = Modifier
                        .align(Alignment.CenterVertically),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.info,
                )
            }

            WatchtowerStatusIcon.WARNING -> {
                Icon(
                    Icons.Outlined.Warning,
                    modifier = Modifier
                        .align(Alignment.CenterVertically),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.warning,
                )
            }

            WatchtowerStatusIcon.ERROR -> {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    modifier = Modifier
                        .align(Alignment.CenterVertically),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    Spacer(
        modifier = Modifier
            .width(8.dp),
    )
}

@Composable
private fun ContentLayout(
    contentPadding: PaddingValues,
    dashboardContent: @Composable ColumnScope.() -> Unit,
    cardsContent: @Composable () -> Unit,
    cardsContent2: @Composable () -> Unit,
    loaded: State<Boolean>,
) {
    val scrollState = rememberScrollState()
    BoxWithConstraints {
        val navBarWithImeInsets = WindowInsets.leNavigationBars
            .union(WindowInsets.leIme)
            .only(WindowInsetsSides.Bottom)
        Box(
            modifier = Modifier
                .windowInsetsPadding(navBarWithImeInsets)
                .consumeWindowInsets(navBarWithImeInsets),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(contentPadding),
            ) {
                val columns = (this@BoxWithConstraints.maxWidth / preferredGridWidth)
                    .roundToInt()
                    .coerceAtLeast(1)
                val dashboardWidth = this@BoxWithConstraints.maxWidth / columns *
                        2.coerceAtMost(columns)

                Column(
                    modifier = Modifier
                        .widthIn(max = dashboardWidth)
                        .fillMaxWidth(),
                ) {
                    dashboardContent()
                }
                Section(
                    text = stringResource(Res.strings.watchtower_section_security_label),
                )
                GridLayout(
                    modifier = Modifier
                        .padding(horizontal = 8.dp),
                    columns = columns,
                    mainAxisSpacing = 8.dp,
                    crossAxisSpacing = 8.dp,
                ) {
                    cardsContent()
                }
                Section(
                    text = stringResource(Res.strings.watchtower_section_maintenance_label),
                )
                GridLayout(
                    modifier = Modifier
                        .padding(horizontal = 8.dp),
                    columns = columns,
                    mainAxisSpacing = 8.dp,
                    crossAxisSpacing = 8.dp,
                ) {
                    cardsContent2()
                }
            }
            AnimatedVisibility(
                modifier = Modifier
                    .padding(contentPadding),
                visible = loaded.value,
                enter = fadeIn() + expandIn(
                    initialSize = {
                        IntSize(it.width, 0)
                    },
                ),
                exit = shrinkOut(
                    targetSize = {
                        IntSize(it.width, 0)
                    },
                ) + fadeOut(),
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    number: Int,
    title: @Composable RowScope.() -> Unit,
    text: String,
    imageVector: ImageVector?,
    content: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (number > 0) 1.dp else 0.dp,
    ) {
        if (imageVector != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
                contentAlignment = Alignment.TopEnd,
            ) {
                Icon(
                    imageVector,
                    modifier = Modifier
                        .size(128.dp)
                        .alpha(0.035f),
                    contentDescription = null,
                )
            }
        }
        Column(
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier
                            .clickable(role = Role.Button) {
                                onClick()
                            }
                    } else {
                        Modifier
                    },
                )
                .padding(8.dp),
        ) {
            val localEmphasis = DefaultEmphasisAlpha
            val localTextStyle = TextStyle(
                color = LocalContentColor.current
                    .combineAlpha(localEmphasis),
            )

            val numberStr = animatedNumberText(number)
            Text(
                numberStr,
                style = MaterialTheme.typography.displayLarge
                    .merge(localTextStyle),
            )
            Spacer(modifier = Modifier.height(8.dp))
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.titleMedium
                    .merge(localTextStyle),
            ) {
                Row {
                    title()
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium
                    .merge(localTextStyle),
            )
            if (content != null) {
                Spacer(modifier = Modifier.height(8.dp).weight(1f))
                content()
            }
        }
    }
}

@Composable
fun CardSkeleton(
    modifier: Modifier = Modifier,
) {
    val contentColor =
        LocalContentColor.current.copy(alpha = DisabledEmphasisAlpha)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier
                .shimmer()
                .padding(8.dp),
        ) {
            Box(
                Modifier
                    .padding(top = 16.dp)
                    .height(48.dp)
                    .width(30.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor),
            )
            Spacer(modifier = Modifier.height(18.dp))
            Box(
                Modifier
                    .height(18.dp)
                    .fillMaxWidth(0.72f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                Modifier
                    .height(14.dp)
                    .fillMaxWidth(0.6f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor.copy(alpha = 0.2f)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                Modifier
                    .height(14.dp)
                    .fillMaxWidth(0.6f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor.copy(alpha = 0.2f)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                Modifier
                    .height(14.dp)
                    .fillMaxWidth(0.6f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor.copy(alpha = 0.2f)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                Modifier
                    .height(14.dp)
                    .fillMaxWidth(0.3f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(contentColor.copy(alpha = 0.2f)),
            )
        }
    }
}
