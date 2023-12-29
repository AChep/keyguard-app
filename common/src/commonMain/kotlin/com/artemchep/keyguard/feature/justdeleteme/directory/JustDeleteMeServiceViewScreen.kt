package com.artemchep.keyguard.feature.justdeleteme.directory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.justdeleteme.AhDifficulty
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.tfa.directory.FlatLaunchBrowserItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.markdown.Md
import com.artemchep.keyguard.ui.poweredby.PoweredByJustDeleteMe
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import dev.icerock.moko.resources.compose.stringResource

@Composable
fun JustDeleteMeScreen(
    args: JustDeleteMeServiceViewRoute.Args,
) {
    val loadableState = produceJustDeleteMeServiceViewState(
        args = args,
    )
    Dialog(
        icon = icon(Icons.Outlined.AccountBox, Icons.Outlined.Delete),
        title = {
            val title = args.justDeleteMe.name
            Text(
                text = title,
            )
            Text(
                text = stringResource(Res.strings.justdeleteme_title),
                style = MaterialTheme.typography.titleSmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
            AhDifficulty(
                modifier = Modifier
                    .padding(top = 4.dp),
                model = args.justDeleteMe,
            )
        },
        content = {
            Column {
                val notes = args.justDeleteMe.notes
                if (notes != null) {
                    Md(
                        modifier = Modifier
                            .padding(horizontal = Dimens.horizontalPadding),
                        markdown = notes,
                    )
                    Spacer(
                        modifier = Modifier
                            .height(16.dp),
                    )
                }

                val navigationController by rememberUpdatedState(LocalNavigationController.current)

                val updatedEmail by rememberUpdatedState(args.justDeleteMe.email)
                if (updatedEmail != null) {
                    FlatItem(
                        elevation = 8.dp,
                        leading = {
                            IconBox(Icons.Outlined.Email)
                        },
                        title = {
                            Text(
                                text = stringResource(Res.strings.justdeleteme_send_email_title),
                            )
                        },
                        trailing = {
                            ChevronIcon()
                        },
                        onClick = {
                            // Open the email, if it's available.
                            updatedEmail?.let {
                                val intent = NavigationIntent.NavigateToEmail(
                                    it,
                                    subject = args.justDeleteMe.emailSubject,
                                    body = args.justDeleteMe.emailBody,
                                )
                                navigationController.queue(intent)
                            }
                        },
                        enabled = updatedEmail != null,
                    )
                }

                val websiteUrl = args.justDeleteMe.url
                if (websiteUrl != null) {
                    FlatLaunchBrowserItem(
                        title = stringResource(Res.strings.uri_action_launch_website_title),
                        url = websiteUrl,
                    )
                }

                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )

                PoweredByJustDeleteMe(
                    modifier = Modifier
                        .padding(horizontal = Dimens.horizontalPadding)
                        .fillMaxWidth(),
                )
            }
        },
        contentScrollable = true,
        actions = {
            val updatedOnClose by rememberUpdatedState(loadableState.getOrNull()?.onClose)
            TextButton(
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.strings.close))
            }
        },
    )
}
