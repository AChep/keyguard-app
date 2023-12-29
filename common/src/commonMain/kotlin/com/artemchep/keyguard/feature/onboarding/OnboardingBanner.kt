// package com.artemchep.keyguard.feature.onboarding
//
// import androidx.compose.foundation.background
// import androidx.compose.foundation.clickable
// import androidx.compose.foundation.layout.Column
// import androidx.compose.foundation.layout.Row
// import androidx.compose.foundation.layout.Spacer
// import androidx.compose.foundation.layout.fillMaxWidth
// import androidx.compose.foundation.layout.padding
// import androidx.compose.foundation.layout.width
// import androidx.compose.material.icons.Icons
// import androidx.compose.material.icons.outlined.Info
// import androidx.compose.material.icons.outlined.NotificationsOff
// import androidx.compose.material3.Icon
// import androidx.compose.material3.LocalContentColor
// import androidx.compose.material3.MaterialTheme
// import androidx.compose.material3.Surface
// import androidx.compose.material3.Text
// import androidx.compose.runtime.Composable
// import androidx.compose.runtime.State
// import androidx.compose.runtime.collectAsState
// import androidx.compose.runtime.derivedStateOf
// import androidx.compose.runtime.getValue
// import androidx.compose.runtime.mutableStateOf
// import androidx.compose.runtime.remember
// import androidx.compose.runtime.rememberUpdatedState
// import androidx.compose.ui.Alignment
// import androidx.compose.ui.Modifier
// import androidx.compose.ui.unit.dp
// import com.artemchep.keyguard.common.usecase.GetOnboardingLastVisitInstant
// import com.artemchep.keyguard.feature.home.security.watchtowerInfoColor
// import com.artemchep.keyguard.feature.home.security.watchtowerWarningColor
// import com.artemchep.keyguard.feature.navigation.LocalNavigationController
// import com.artemchep.keyguard.feature.navigation.NavigationIntent
// import com.artemchep.keyguard.ui.ChevronIcon
// import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
// import com.artemchep.keyguard.ui.MediumEmphasisAlpha
// import com.artemchep.keyguard.ui.theme.Dimens
// import com.artemchep.keyguard.ui.theme.combineAlpha
// import com.artemchep.keyguard.ui.util.HorizontalDivider
// import com.google.accompanist.permissions.ExperimentalPermissionsApi
// import com.google.accompanist.permissions.PermissionStatus
// import com.google.accompanist.permissions.rememberPermissionState
// import kotlinx.coroutines.flow.map
// import org.kodein.di.compose.rememberInstance
//
// @OptIn(ExperimentalPermissionsApi::class)
// @Composable
// fun OnboardingBanner(
//    modifier: Modifier = Modifier,
//    contentModifier: Modifier = Modifier,
// ) {
//    val getInstant by rememberInstance<GetOnboardingLastVisitInstant>()
//    val visible = remember(getInstant) {
//        getInstant()
//            .map { it == null }
//    }.collectAsState(initial = false)
//
//    val navigationController by rememberUpdatedState(LocalNavigationController.current)
//    ExpandedIfNotEmpty(
//        modifier = modifier,
//        valueOrNull = Unit.takeIf { visible.value },
//    ) {
//        OnboardingBanner(
//            contentModifier = contentModifier,
//            onClick = {
//                val intent = NavigationIntent.NavigateToRoute(
//                    route = OnboardingRoute,
//                )
//                navigationController.queue(intent)
//            },
//        )
//    }
// }
//
// @Composable
// fun rememberOnboardingBannerState(): State<Boolean> {
//    val getInstant by rememberInstance<GetOnboardingLastVisitInstant>()
//    val bannerState = remember(getInstant) {
//        getInstant()
//            .map { it == null }
//    }.collectAsState(initial = false)
//    return bannerState
// }
//
// @Composable
// fun OnboardingBanner(
//    modifier: Modifier = Modifier,
//    contentModifier: Modifier = Modifier,
//    onClick: () -> Unit,
// ) {
//    Surface(
//        modifier = modifier,
//        tonalElevation = 3.dp,
//    ) {
//        Column {
//            val warningColor = watchtowerInfoColor()
//            val backgroundColor = warningColor
//                .copy(alpha = 0.13f)
//            Row(
//                modifier = Modifier
//                    .fillMaxWidth()
//                    .background(backgroundColor)
//                    .clickable {
//                        onClick()
//                    }
//                    .then(contentModifier)
//                    .padding(
//                        vertical = 8.dp,
//                        horizontal = Dimens.horizontalPadding,
//                    ),
//                verticalAlignment = Alignment.CenterVertically,
//            ) {
//                Icon(
//                    imageVector = Icons.Outlined.Info,
//                    contentDescription = null,
//                )
//                Spacer(
//                    modifier = Modifier
//                        .width(Dimens.horizontalPadding),
//                )
//                Column(
//                    modifier = Modifier
//                        .weight(1f),
//                ) {
//                    Text(
//                        text = "Learn more about app's features",
//                        style = MaterialTheme.typography.titleMedium,
//                    )
// //                    Text(
// //                        text = "Grant the notification permission to allow Keyguard to show one-time passwords when autofilling & more.",
// //                        style = MaterialTheme.typography.bodySmall,
// //                        color = LocalContentColor.current
// //                            .combineAlpha(MediumEmphasisAlpha),
// //                    )
//                }
//                Spacer(
//                    modifier = Modifier
//                        .width(Dimens.horizontalPadding),
//                )
//                ChevronIcon()
//            }
//            HorizontalDivider()
//        }
//    }
// }
