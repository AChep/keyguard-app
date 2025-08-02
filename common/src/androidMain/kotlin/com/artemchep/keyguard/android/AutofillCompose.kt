package com.artemchep.keyguard.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.LinkInfoAndroid
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.feature.favicon.FaviconImage
import com.artemchep.keyguard.feature.favicon.FaviconUrl
import com.artemchep.keyguard.feature.keyguard.AppRoute
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.composable
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.kodein.di.allInstances
import org.kodein.di.compose.rememberDI

@Composable
fun AutofillScaffold(
    topBar: @Composable () -> Unit,
) {
    ExtensionScaffold(
        header = {
            topBar()
        },
    ) {
        NavigationNode(
            id = "App:Autofill",
            route = AppRoute,
        )
    }
}

@Composable
fun PasskeyScaffold(
    topBar: @Composable () -> Unit,
) {
    ExtensionScaffold(
        header = {
            topBar()
        },
    ) {
        NavigationNode(
            id = "App:Passkey",
            route = AppRoute,
        )
    }
}

@Composable
fun ExtensionScaffold(
    header: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val topInsets = WindowInsets.systemBars
        .only(WindowInsetsSides.Top)
    Column(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .windowInsetsPadding(topInsets),
    ) {
        val horizontalInsets = WindowInsets.statusBars
            .union(WindowInsets.navigationBars)
            .union(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal)
        Box(
            modifier = Modifier
                .windowInsetsPadding(horizontalInsets),
        ) {
            header()
        }

        val shape = MaterialTheme.shapes.extraLarge
            .copy(
                bottomStart = CornerSize(0.dp),
                bottomEnd = CornerSize(0.dp),
            )
        Box(
            modifier = Modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.background)
                .fillMaxSize(),
        ) {
            content()
        }
    }
}

@Composable
fun AppInfo(
    packageName: String?,
    webDomain: String?,
    webScheme: String?,
) {
    when {
        webDomain != null && webScheme != null -> {
            AppInfoWeb(
                webDomain = webDomain,
                webScheme = webScheme,
            )
        }

        packageName != null -> {
            AppInfoAndroid(
                packageName = packageName,
            )
        }

        else -> {
            Text(
                text = "Autofill with Keyguard",
                style = MaterialTheme.typography.titleMedium,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun AppInfoWeb(
    webDomain: String,
    webScheme: String,
) {
    var state by remember(webDomain) {
        mutableStateOf<Loadable<AppInfoData>>(Loadable.Loading)
    }

    LaunchedEffect(webDomain) {
        val data = AppInfoData(
            label = webDomain,
            icon = {
                FaviconImage(
                    modifier = Modifier
                        .clip(CircleShape),
                    imageModel = {
                        FaviconUrl(
                            url = "$webScheme://$webDomain",
                        )
                    },
                )
            },
        )
        state = Loadable.Ok(data)
    }

    AppInfo(
        state = state,
    )
}

@Composable
private fun AppInfoAndroid(
    packageName: String,
) {
    val linkInfoExtractors: List<LinkInfoExtractor<LinkInfoPlatform.Android, LinkInfoAndroid>> by rememberDI {
        allInstances()
    }

    var state by remember(packageName) {
        mutableStateOf<Loadable<AppInfoData>>(Loadable.Loading)
    }

    LaunchedEffect(packageName) {
        val linkInfo = LinkInfoPlatform.Android(packageName)
        val linkInfoData = linkInfoExtractors
            .filter { it.from == LinkInfoPlatform.Android::class && it.handles(linkInfo) }
            .firstNotNullOfOrNull { extractor ->
                val model = extractor
                    .extractInfo(linkInfo)
                    .attempt()
                    .bind()
                    .getOrNull()
                when (model) {
                    is LinkInfoAndroid.Installed ->
                        AppInfoData(
                            label = model.label,
                            icon = {
                                Image(
                                    modifier = Modifier
                                        .clip(CircleShape),
                                    painter = model.icon,
                                    contentDescription = null,
                                )
                            },
                        )

                    else -> null
                }
            }
            ?: AppInfoData(label = packageName)
        state = Loadable.Ok(linkInfoData)
    }

    AppInfo(
        state = state,
    )
}

@Composable
private fun AppInfo(
    state: Loadable<AppInfoData>,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = remember(state) {
            when (state) {
                is Loadable.Loading ->
                    composable {
                        KeyguardLoadingIndicator()
                    }

                is Loadable.Ok -> if (state.value.icon != null) {
                    composable {
                        state.value.icon.invoke()
                    }
                } else {
                    // do not show an icon
                    null
                }
            }
        }
        ExpandedIfNotEmptyForRow(valueOrNull = icon) { content ->
            Row {
                Box(
                    modifier = Modifier
                        .size(24.dp),
                ) {
                    content()
                }
                Spacer(
                    modifier = Modifier
                        .width(16.dp),
                )
            }
        }
        Column {
            Text(
                text = "Autofill for",
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            if (state is Loadable.Ok) {
                Text(
                    text = state.value.label,
                    style = MaterialTheme.typography.titleMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        }
    }
}

private class AppInfoData(
    val label: String,
    val icon: (@Composable () -> Unit)? = null,
)
