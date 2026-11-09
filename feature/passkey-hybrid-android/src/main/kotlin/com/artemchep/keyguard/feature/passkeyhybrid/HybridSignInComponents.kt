package com.artemchep.keyguard.feature.passkeyhybrid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.model.VaultItemIcon
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridOperation
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridSessionState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.passkey_create_header
import com.artemchep.keyguard.res.passkey_hybrid_request_sign_in
import com.artemchep.keyguard.ui.AvatarBuilder
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardPasskey
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

/**
 * The state of the flow at a glance: an expressive icon,
 * a title that is announced when it changes, and a detail.
 */
@Composable
internal fun HybridStatusCard(
    icon: @Composable () -> Unit,
    title: String,
    text: String,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.contentPadding)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.extraLarge,
            )
            .padding(
                horizontal = Dimens.horizontalPadding,
                vertical = 20.dp,
            ),
    ) {
        icon()
        Spacer(
            modifier = Modifier
                .height(Dimens.verticalPadding),
        )
        Text(
            modifier = Modifier
                .semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(
            modifier = Modifier
                .height(4.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        if (content != null) {
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
            content()
        }
    }
}

/** The website the nearby browser asked about, once its request is known. */
@Composable
internal fun HybridRequestHeader(state: HybridSessionState) {
    val rpId = state.rpId
        ?: return
    val label =
        when (state.operation) {
            HybridOperation.SIGN_IN -> Res.string.passkey_hybrid_request_sign_in
            HybridOperation.CREATE -> Res.string.passkey_create_header
            null -> null
        }
    val icon =
        remember(rpId) {
            VaultItemIcon.WebsiteIcon(
                data = FaviconUrl(url = "https://$rpId"),
                fallback = VaultItemIcon.VectorIcon(Icons.Outlined.KeyguardPasskey),
            )
        }
    FlatItemLayoutExpressive(
        // Context for the page, not a card of its own.
        backgroundColor = Color.Transparent,
        leading = {
            AvatarBuilder(
                icon = icon,
                active = true,
                badge = {
                    // Do nothing.
                },
            )
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(rpId)
                },
                text = if (label != null) {
                    // composable
                    {
                        Text(stringResource(label))
                    }
                } else {
                    null
                },
            )
        },
        // A static row, not a disabled one.
        enabled = true,
    )
}

@Composable
internal fun HybridFootnote(text: String) {
    val color = LocalContentColor.current.combineAlpha(MediumEmphasisAlpha)
    Column(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = color,
        )
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
    }
}
