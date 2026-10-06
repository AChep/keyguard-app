package com.artemchep.keyguard.ui.button

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.webdav_settings_test_text
import com.artemchep.keyguard.res.webdav_settings_test_text_read_only
import com.artemchep.keyguard.res.webdav_settings_test_title
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardConnectionTest
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.verticalPaddingHalf
import org.jetbrains.compose.resources.stringResource

/**
 * The connection test button and its note. A [readOnly] test only reads;
 * any other test writes and removes a probe.
 */
fun LazyListScope.connectionTestItems(
    onClick: () -> Unit,
    enabled: Boolean,
    readOnly: Boolean,
) {
    item("connection.header") {
        Spacer(
            modifier = Modifier
                .height(32.dp),
        )
    }
    item("connection.test") {
        ConnectionTestButton(
            onClick = onClick,
            enabled = enabled,
        )
    }
    item("connection.test.info") {
        Text(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.textHorizontalPadding,
                    vertical = Dimens.verticalPaddingHalf,
                ),
            text = if (readOnly) {
                stringResource(Res.string.webdav_settings_test_text_read_only)
            } else {
                stringResource(Res.string.webdav_settings_test_text)
            },
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            style = MaterialTheme.typography.bodySmall,
            fontSize = 13.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectionTestButton(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Button(
        modifier = Modifier
            .padding(horizontal = Dimens.buttonHorizontalPadding),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(),
        shapes = ButtonDefaults.shapes(),
        elevation = ButtonDefaults.buttonElevation(),
        enabled = enabled,
    ) {
        Box(
            modifier = Modifier
                .size(ButtonDefaults.IconSize),
        ) {
            Icon(
                imageVector = Icons.Outlined.KeyguardConnectionTest,
                contentDescription = null,
            )
        }
        Spacer(
            modifier = Modifier
                .width(ButtonDefaults.IconSpacing),
        )
        Text(
            text = stringResource(Res.string.webdav_settings_test_title),
        )
    }
}
