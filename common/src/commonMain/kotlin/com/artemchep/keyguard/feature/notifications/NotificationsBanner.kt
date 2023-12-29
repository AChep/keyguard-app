// package com.artemchep.keyguard.feature.notifications
//
// import android.Manifest
// import android.os.Build
// import androidx.compose.foundation.background
// import androidx.compose.foundation.clickable
// import androidx.compose.foundation.layout.Column
// import androidx.compose.foundation.layout.Row
// import androidx.compose.foundation.layout.Spacer
// import androidx.compose.foundation.layout.fillMaxWidth
// import androidx.compose.foundation.layout.padding
// import androidx.compose.foundation.layout.width
// import androidx.compose.material.icons.Icons
// import androidx.compose.material.icons.outlined.NotificationsOff
// import androidx.compose.material3.Icon
// import androidx.compose.material3.LocalContentColor
// import androidx.compose.material3.MaterialTheme
// import androidx.compose.material3.Surface
// import androidx.compose.material3.Text
// import androidx.compose.runtime.Composable
// import androidx.compose.runtime.State
// import androidx.compose.runtime.derivedStateOf
// import androidx.compose.runtime.mutableStateOf
// import androidx.compose.runtime.remember
// import androidx.compose.ui.Alignment
// import androidx.compose.ui.Modifier
// import androidx.compose.ui.unit.dp
// import com.artemchep.keyguard.feature.home.security.watchtowerWarningColor
// import com.artemchep.keyguard.ui.ChevronIcon
// import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
// import com.artemchep.keyguard.ui.MediumEmphasisAlpha
// import com.artemchep.keyguard.ui.theme.Dimens
// import com.artemchep.keyguard.ui.theme.combineAlpha
// import com.artemchep.keyguard.ui.util.HorizontalDivider
// import com.google.accompanist.permissions.ExperimentalPermissionsApi
// import com.google.accompanist.permissions.PermissionStatus
// import com.google.accompanist.permissions.rememberPermissionState
//
// @OptIn(ExperimentalPermissionsApi::class)
// @Composable
// fun NotificationsBanner(
//    modifier: Modifier = Modifier,
//    contentModifier: Modifier = Modifier,
// ) {
//    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
//        return
//    }
//
//    val state = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
//    val visible = state.status !is PermissionStatus.Granted
//    ExpandedIfNotEmpty(
//        modifier = modifier,
//        valueOrNull = Unit.takeIf { visible },
//    ) {
//        NotificationsBanner(
//            contentModifier = contentModifier,
//            onClick = {
//                state.launchPermissionRequest()
//            },
//        )
//    }
// }
//
// @OptIn(ExperimentalPermissionsApi::class)
// @Composable
// fun rememberNotificationsBannerState(): State<Boolean> {
//    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
//        return remember {
//            mutableStateOf(false)
//        }
//    }
//
//    val permissionState = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
//    val bannerState = remember(permissionState) {
//        derivedStateOf {
//            permissionState.status !is PermissionStatus.Granted
//        }
//    }
//    return bannerState
// }
//
// @Composable
// private fun NotificationsBanner(
//    modifier: Modifier = Modifier,
//    contentModifier: Modifier = Modifier,
//    onClick: () -> Unit,
// ) {
//    Surface(
//        modifier = modifier,
//        tonalElevation = 3.dp,
//    ) {
//        Column {
//            val warningColor = watchtowerWarningColor()
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
//                    imageVector = Icons.Outlined.NotificationsOff,
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
//                        text = "Notifications are disabled",
//                        style = MaterialTheme.typography.titleMedium,
//                    )
//                    Text(
//                        text = "Grant the notification permission to allow Keyguard to show one-time passwords when autofilling & more.",
//                        style = MaterialTheme.typography.bodySmall,
//                        color = LocalContentColor.current
//                            .combineAlpha(MediumEmphasisAlpha),
//                    )
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
