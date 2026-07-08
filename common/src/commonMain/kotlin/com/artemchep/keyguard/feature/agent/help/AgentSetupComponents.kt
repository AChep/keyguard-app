package com.artemchep.keyguard.feature.agent.help

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.copy
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import com.artemchep.keyguard.ui.util.HorizontalDivider
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.compose.rememberInstance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentSetupScaffold(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = title,
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
        columnContent = content,
    )
}

@Composable
internal fun AgentSetupBodyLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier
            .padding(
                horizontal = Dimens.textHorizontalPadding,
            ),
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalContentColor.current
            .combineAlpha(MediumEmphasisAlpha),
    )
}

@Composable
internal fun AgentSetupParagraph(
    text: String,
) {
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.textHorizontalPadding),
        text = text,
    )
}

@Composable
internal fun AgentSetupCodeBlock(
    text: String,
    file: String? = null,
) {
    val codeModifier = if (CurrentPlatform is Platform.Mobile) {
        // On mobile the gesture of swiping to reveal more is trivial
        // and intuitive, on desktop however that is most likely
        // blocked.
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    } else {
        Modifier
    }

    val clipboardService by rememberInstance<ClipboardService>()
    val copyDescription = stringResource(Res.string.copy)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                vertical = 4.dp,
                horizontal = Dimens.textHorizontalPadding,
            )
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = true),
        ) {
            if (file != null) {
                Text(
                    modifier = Modifier
                        .alpha(DisabledEmphasisAlpha),
                    text = file,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(
                    modifier = Modifier
                        .height(8.dp),
                )
            }
            SelectionContainer {
                val isDark = MaterialTheme.colorScheme.isDark
                var code by remember(text) {
                    mutableStateOf(AnnotatedString(text))
                }

                // Async-ly highlight the syntax of the
                // code snippet.
                LaunchedEffect(text, isDark) {
                    runCatching {
                        code = withContext(Dispatchers.Default) {
                            val codeHighlights = Highlights.Builder()
                                .code(text)
                                .theme(SyntaxThemes.darcula(darkMode = isDark))
                                .language(SyntaxLanguage.SHELL)
                                .build()

                            buildAnnotatedString {
                                append(text)

                                // Highlight & bold special segments
                                codeHighlights.getHighlights()
                                    .filterIsInstance<ColorHighlight>()
                                    .forEach {
                                        addStyle(
                                            SpanStyle(color = Color(it.rgb).copy(alpha = 1f)),
                                            start = it.location.start,
                                            end = it.location.end,
                                        )
                                    }
                                codeHighlights.getHighlights()
                                    .filterIsInstance<BoldHighlight>()
                                    .forEach {
                                        addStyle(
                                            SpanStyle(fontWeight = FontWeight.Bold),
                                            start = it.location.start,
                                            end = it.location.end,
                                        )
                                    }
                            }
                        }
                    }
                }
                Text(
                    modifier = Modifier
                        .then(codeModifier),
                    text = code,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        IconButton(
            onClick = {
                clipboardService.setPrimaryClip(
                    value = text,
                    concealed = false,
                )
            },
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = copyDescription,
            )
        }
    }
}

@Composable
internal fun AgentSetupSectionDivider() {
    HorizontalDivider(
        modifier = Modifier
            .padding(vertical = 16.dp),
    )
}
