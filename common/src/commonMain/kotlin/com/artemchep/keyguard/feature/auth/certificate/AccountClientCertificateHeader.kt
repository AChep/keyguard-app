package com.artemchep.keyguard.feature.auth.certificate

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.GppBad
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.home.vault.component.FlatSurfaceExpressive
import com.artemchep.keyguard.feature.home.vault.component.VaultViewOutlinedBadge
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.client_certificate_add
import com.artemchep.keyguard.res.client_certificate_empty_text
import com.artemchep.keyguard.res.client_certificate_empty_title
import com.artemchep.keyguard.res.client_certificate_expired
import com.artemchep.keyguard.res.client_certificate_file_types
import com.artemchep.keyguard.res.client_certificate_issuer
import com.artemchep.keyguard.res.client_certificate_unsaved
import com.artemchep.keyguard.res.expires
import com.artemchep.keyguard.res.valid_from
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.theme.okContainer
import com.artemchep.keyguard.ui.theme.onOkContainer
import com.artemchep.keyguard.ui.theme.onWarningContainer
import com.artemchep.keyguard.ui.theme.warning
import com.artemchep.keyguard.ui.theme.warningContainer
import org.jetbrains.compose.resources.stringResource

private val ClientCertificateIconSize = 56.dp

private const val CLIENT_CERTIFICATE_TRACK_ALPHA = 0.24f

/**
 * The certificate at a glance: its health, who issued it and how
 * much of its validity period is left. Starts a segmented group.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClientCertificateHeader(
    certificate: AccountClientCertificateState.Certificate,
    unsaved: Boolean,
    modifier: Modifier = Modifier,
) {
    val appearance = clientCertificateAppearance(certificate.status)
    val accent by animateColorAsState(
        targetValue = appearance.accent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    FlatSurfaceExpressive(
        modifier = modifier,
        shapeState = ShapeState.START,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = Dimens.contentPadding,
                    vertical = 20.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ClientCertificateIcon(
                    icon = appearance.icon,
                    containerColor = appearance.container,
                    contentColor = appearance.onContainer,
                )
                ClientCertificateTitle(
                    modifier = Modifier
                        .weight(1f),
                    certificate = certificate,
                    unsaved = unsaved,
                )
            }
            if (certificate.status == ClientCertificateStatus.EXPIRED) {
                Text(
                    text = stringResource(Res.string.client_certificate_expired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (certificate.progress != null) {
                ClientCertificateValidity(
                    progress = certificate.progress,
                    validFrom = certificate.validFrom,
                    expiresOn = certificate.expiresOn,
                    color = accent,
                )
            }
        }
    }
}

@Composable
private fun ClientCertificateTitle(
    certificate: AccountClientCertificateState.Certificate,
    unsaved: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        Text(
            text = certificate.displayName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (certificate.issuerName != null) {
            Text(
                text = stringResource(Res.string.client_certificate_issuer, certificate.issuerName),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (unsaved) {
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            VaultViewOutlinedBadge(
                text = stringResource(Res.string.client_certificate_unsaved),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClientCertificateValidity(
    progress: Float,
    validFrom: String?,
    expiresOn: String?,
    color: Color,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = MaterialTheme.motionScheme.slowSpatialSpec(),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LinearWavyProgressIndicator(
            modifier = Modifier
                .fillMaxWidth(),
            progress = { animatedProgress },
            color = color,
            trackColor = color.combineAlpha(CLIENT_CERTIFICATE_TRACK_ALPHA),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ClientCertificateDate(
                modifier = Modifier
                    .weight(1f),
                label = stringResource(Res.string.valid_from),
                value = validFrom,
                textAlign = TextAlign.Start,
            )
            ClientCertificateDate(
                modifier = Modifier
                    .weight(1f),
                label = stringResource(Res.string.expires),
                value = expiresOn,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun ClientCertificateDate(
    label: String,
    value: String?,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            textAlign = textAlign,
        )
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = value.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = textAlign,
        )
    }
}

/** Shown instead of the header while no certificate is set. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClientCertificateEmptyCard(
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraLarge,
            )
            .padding(
                horizontal = Dimens.horizontalPadding,
                vertical = 20.dp,
            ),
    ) {
        ClientCertificateIcon(
            icon = Icons.Outlined.Shield,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(
            modifier = Modifier
                .height(Dimens.verticalPadding),
        )
        Text(
            text = stringResource(Res.string.client_certificate_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        Text(
            text = stringResource(Res.string.client_certificate_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(
            modifier = Modifier
                .height(20.dp),
        )
        ClientCertificateAddButton(
            onClick = onAdd,
        )
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = stringResource(Res.string.client_certificate_file_types),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClientCertificateAddButton(
    onClick: (() -> Unit)?,
) {
    Button(
        modifier = Modifier
            .fillMaxWidth(),
        shapes = ButtonDefaults.shapes(),
        enabled = onClick != null,
        onClick = {
            onClick?.invoke()
        },
    ) {
        Icon(
            modifier = Modifier
                .size(ButtonDefaults.IconSize),
            imageVector = Icons.Outlined.FileOpen,
            contentDescription = null,
        )
        Spacer(
            modifier = Modifier
                .size(ButtonDefaults.IconSpacing),
        )
        Text(
            text = stringResource(Res.string.client_certificate_add),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClientCertificateIcon(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
) {
    val container by animateColorAsState(
        targetValue = containerColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    val content by animateColorAsState(
        targetValue = contentColor,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    )
    Box(
        modifier = Modifier
            .size(ClientCertificateIconSize)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
        )
    }
}

private data class ClientCertificateAppearance(
    val icon: ImageVector,
    val accent: Color,
    val container: Color,
    val onContainer: Color,
)

@Composable
private fun clientCertificateAppearance(
    status: ClientCertificateStatus?,
): ClientCertificateAppearance = when (status) {
    ClientCertificateStatus.VALID -> ClientCertificateAppearance(
        icon = Icons.Outlined.GppGood,
        accent = MaterialTheme.colorScheme.ok,
        container = MaterialTheme.colorScheme.okContainer,
        onContainer = MaterialTheme.colorScheme.onOkContainer,
    )

    ClientCertificateStatus.EXPIRING_SOON -> ClientCertificateAppearance(
        icon = Icons.Outlined.GppMaybe,
        accent = MaterialTheme.colorScheme.warning,
        container = MaterialTheme.colorScheme.warningContainer,
        onContainer = MaterialTheme.colorScheme.onWarningContainer,
    )

    ClientCertificateStatus.EXPIRED,
    ClientCertificateStatus.NOT_YET_VALID,
    null,
    -> ClientCertificateAppearance(
        icon = Icons.Outlined.GppBad,
        accent = MaterialTheme.colorScheme.error,
        container = MaterialTheme.colorScheme.errorContainer,
        onContainer = MaterialTheme.colorScheme.onErrorContainer,
    )
}
