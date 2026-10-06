package com.artemchep.keyguard.feature.s3

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.verticalPaddingHalf

internal fun MutableState<String>.toTextFieldModel(
    hint: String? = null,
    error: String? = null,
) = TextFieldModel(
    text = value,
    hint = hint,
    error = error,
    onChange = ::value::set,
)

internal fun LazyListScope.gap(
    key: String,
) {
    item(key) {
        Spacer(
            modifier = Modifier
                .height(3.dp),
        )
    }
}

internal fun LazyListScope.note(
    key: String,
    header: Boolean = false,
    text: @Composable () -> String,
) {
    item(key) {
        Text(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.textHorizontalPadding,
                    vertical = if (header) Dimens.verticalPadding else Dimens.verticalPaddingHalf,
                ),
            text = text(),
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}

/** The message of this form error, when one of the [owners] fields owns it. */
@Composable
internal fun S3FormError?.textIfOwnedBy(
    vararg owners: S3FormError,
): String? = this
    ?.takeIf { it in owners }
    ?.let { s3FormErrorText(it) }
