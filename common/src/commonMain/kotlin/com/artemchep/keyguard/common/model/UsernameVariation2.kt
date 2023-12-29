package com.artemchep.keyguard.common.model

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Email
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.feature.favicon.GravatarUrl
import com.artemchep.keyguard.ui.icons.EmailIcon
import com.artemchep.keyguard.ui.icons.IconBox

sealed interface UsernameVariation2 {
    data class Email(
        val gravatarUrl: GravatarUrl? = null,
    ) : UsernameVariation2

    data object Username : UsernameVariation2

    data object Phone : UsernameVariation2

    companion object {
        val default get() = Username

        suspend fun of(
            getGravatarUrl: GetGravatarUrl,
            username: String,
        ) = when (UsernameVariation.of(username)) {
            UsernameVariation.EMAIL -> {
                val gravatarUrl = getGravatarUrl(username)
                    .attempt()
                    .bind()
                    .getOrNull()
                Email(
                    gravatarUrl = gravatarUrl,
                )
            }

            UsernameVariation.PHONE -> Phone
            UsernameVariation.USERNAME -> Username
            else -> default
        }
    }
}

val UsernameVariation2.icon
    get() = when (this) {
        is UsernameVariation2.Email -> Icons.Outlined.Email
        is UsernameVariation2.Phone -> Icons.Outlined.Call
        is UsernameVariation2.Username -> Icons.Outlined.AlternateEmail
    }

@Composable
fun UsernameVariationIcon(
    modifier: Modifier = Modifier,
    usernameVariation: UsernameVariation2,
) {
    when (usernameVariation) {
        is UsernameVariation2.Email -> {
            EmailIcon(
                modifier = modifier
                    .size(24.dp)
                    .clip(CircleShape),
                gravatarUrl = usernameVariation.gravatarUrl,
            )
        }

        else -> {
            val icon = usernameVariation.icon
            IconBox(main = icon)
        }
    }
}
