package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.qr.ScanQrRouteFactory
import com.artemchep.keyguard.feature.qr.produceLoadQrStateWithEffects
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_paste_code_label
import com.artemchep.keyguard.res.app_link_pairing_scan_text
import com.artemchep.keyguard.res.continue_
import com.artemchep.keyguard.res.scanqr_load_from_image
import com.artemchep.keyguard.res.scanqr_title
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.currentKoinScope

/**
 * Gets the code of the other device: with the camera where the app can scan,
 * from an image where it can load one, or pasted as text.
 */
@Composable
fun PairingScanPanel(
    step: PairingStep.Scan,
) {
    val updatedOnCode by rememberUpdatedState(step.onCode)

    Text(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = stringResource(Res.string.app_link_pairing_scan_text),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))

    PairingScanSources(
        onCode = { code ->
            updatedOnCode(code)
        },
    )

    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.fieldHorizontalPadding),
        label = stringResource(Res.string.app_link_pairing_paste_code_label),
        value = step.code,
        singleLine = true,
        maxLines = 1,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                step.onSubmit?.invoke()
            },
        ),
        leading = {
            IconBox(
                main = Icons.Outlined.Key,
            )
        },
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        horizontalArrangement = Arrangement.End,
    ) {
        Button(
            enabled = step.onSubmit != null,
            onClick = {
                step.onSubmit?.invoke()
            },
        ) {
            Text(
                text = stringResource(Res.string.continue_),
            )
        }
    }
}

/** The camera, where the app can scan, and an image, where it can load one. */
@Composable
private fun PairingScanSources(
    onCode: (String) -> Unit,
) {
    val onScan = rememberScanQrCodeAction(onCode)
    val onSelectFile = produceLoadQrStateWithEffects(
        onValueChange = onCode,
    )?.onSelectFile
    if (onScan != null) {
        PairingScanSourceItem(
            shapeState = if (onSelectFile != null) ShapeState.START else ShapeState.ALL,
            icon = Icons.Outlined.Camera,
            title = stringResource(Res.string.scanqr_title),
            onClick = onScan,
        )
    }
    if (onSelectFile != null) {
        PairingScanSourceItem(
            shapeState = if (onScan != null) ShapeState.END else ShapeState.ALL,
            icon = Icons.Outlined.FileUpload,
            title = stringResource(Res.string.scanqr_load_from_image),
            onClick = onSelectFile,
        )
    }
    if (onScan != null || onSelectFile != null) {
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Returns an action that scans a QR code with the camera and hands its
 * text to [onCode], or `null` if this app can not scan.
 */
@Composable
private fun rememberScanQrCodeAction(
    onCode: (String) -> Unit,
): (() -> Unit)? {
    val updatedOnCode by rememberUpdatedState(onCode)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    val scanQrRouteFactory = currentKoinScope().getOrNull<ScanQrRouteFactory>()
    return remember(scanQrRouteFactory) {
        scanQrRouteFactory?.let { routeFactory ->
            // lambda
            {
                val route = registerRouteResultReceiver(routeFactory.create()) { rawValue ->
                    controller.queue(NavigationIntent.Pop)
                    updatedOnCode(rawValue)
                }
                val intent = NavigationIntent.NavigateToRoute(
                    route = route,
                )
                controller.queue(intent)
            }
        }
    }
}

@Composable
private fun PairingScanSourceItem(
    shapeState: Int,
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    FlatItemSimpleExpressive(
        elevation = 1.dp,
        shapeState = shapeState,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        title = {
            Text(
                text = title,
            )
        },
        onClick = onClick,
    )
}
