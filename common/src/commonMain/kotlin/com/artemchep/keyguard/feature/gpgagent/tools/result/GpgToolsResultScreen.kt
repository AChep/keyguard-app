package com.artemchep.keyguard.feature.gpgagent.tools.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.SaveAs
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.close
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource

@Composable
fun GpgToolsResultScreen(
    args: GpgToolsResultRoute.Args,
) {
    val updatedNavigationController by rememberUpdatedState(LocalNavigationController.current)
    Dialog(
        title = null,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
                val verification = args.verification
                if (verification != null) {
                    FlatSimpleNote(
                        modifier = Modifier
                            .fillMaxWidth(),
                        note = verification,
                    )
                }

                val output = args.output
                if (output != null) {
                    if (verification != null) {
                        Spacer(
                            modifier = Modifier
                                .height(16.dp),
                        )
                    }
                    SelectionContainer {
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(
                                    vertical = 8.dp,
                                    horizontal = Dimens.textHorizontalPadding,
                                ),
                            text = output.text,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        actions = {
            val output = args.output
            if (output?.onSave != null) {
                val updatedOnSave by rememberUpdatedState(output.onSave)
                IconButton(
                    onClick = {
                        updatedOnSave()
                        updatedNavigationController.queue(NavigationIntent.Pop)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SaveAlt,
                        contentDescription = null,
                    )
                }
            }
            if (output?.onCopy != null) {
                val updatedOnCopy by rememberUpdatedState(output.onCopy)
                IconButton(
                    onClick = {
                        updatedOnCopy()
                        updatedNavigationController.queue(NavigationIntent.Pop)
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
                }
            }
            Spacer(
                modifier = Modifier
                    .weight(1f),
            )
            TextButton(
                onClick = {
                    updatedNavigationController.queue(NavigationIntent.Pop)
                },
            ) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}
