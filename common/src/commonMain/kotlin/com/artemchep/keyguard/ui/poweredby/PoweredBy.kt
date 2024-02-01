package com.artemchep.keyguard.ui.poweredby

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.URL_2FA
import com.artemchep.keyguard.URL_HAVE_I_BEEN_PWNED
import com.artemchep.keyguard.URL_JUST_DELETE_ME
import com.artemchep.keyguard.URL_JUST_GET_MY_DATA
import com.artemchep.keyguard.URL_PASSKEYS
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun PoweredByJustDeleteMe(
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    PoweredByLabel(
        modifier = modifier,
        domain = "JustDeleteMe",
        url = URL_JUST_DELETE_ME,
        fill = fill,
    )
}

@Composable
fun PoweredByJustGetMyData(
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    PoweredByLabel(
        modifier = modifier,
        domain = "JustGetMyData",
        url = URL_JUST_GET_MY_DATA,
        fill = fill,
    )
}

@Composable
fun PoweredByHaveibeenpwned(
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    PoweredByLabel(
        modifier = modifier,
        domain = "HIBP",
        url = URL_HAVE_I_BEEN_PWNED,
        fill = fill,
    )
}

@Composable
fun PoweredBy2factorauth(
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    PoweredByLabel(
        modifier = modifier,
        domain = "2factorauth",
        url = URL_2FA,
        fill = fill,
    )
}

@Composable
fun PoweredByPasskeys(
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    PoweredByLabel(
        modifier = modifier,
        domain = "passkeys",
        url = URL_PASSKEYS,
        fill = fill,
    )
}

@Composable
fun PoweredByLabel(
    modifier: Modifier = Modifier,
    domain: String,
    url: String,
    fill: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier = Modifier
                .weight(1f, fill = fill)
                .padding(top = 4.dp),
            text = stringResource(Res.strings.powered_by),
            maxLines = 2,
            style = MaterialTheme.typography.labelSmall,
            color = LocalContentColor.current
                .combineAlpha(DisabledEmphasisAlpha),
        )
        Spacer(modifier = Modifier.width(8.dp))
        val navigationController by rememberUpdatedState(LocalNavigationController.current)
        val updatedUrl by rememberUpdatedState(url)
        TextButton(
            onClick = {
                val intent = NavigationIntent.NavigateToBrowser(
                    url = updatedUrl,
                )
                navigationController.queue(intent)
            },
        ) {
            Text(
                text = domain,
                maxLines = 1,
            )
        }
    }
}
