package com.artemchep.keyguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.ready_status_beta
import org.jetbrains.compose.resources.stringResource

@Composable
fun BetaBadge(
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier
            .background(MaterialTheme.colorScheme.primary, shape = CircleShape)
            .padding(
                vertical = 1.dp,
                horizontal = 6.dp,
            ),
        text = stringResource(Res.string.ready_status_beta),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimary,
    )
}
