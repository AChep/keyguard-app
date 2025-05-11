package com.artemchep.keyguard.feature.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeFinishing
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.ui.DialogPopup
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.util.HorizontalDivider

// See:
// https://m3.material.io/components/dialogs/specs#9a8c226b-19fa-4d6b-894e-e7d5ca9203e8

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Dialog(
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable ColumnScope.() -> Unit)?,
    content: (@Composable BoxScope.() -> Unit)?,
    contentScrollable: Boolean = true,
    actions: @Composable FlowRowScope.() -> Unit,
) {
    val updatedNavController by rememberUpdatedState(LocalNavigationController.current)
    DialogPopup(
        onDismissRequest = {
            val intent = NavigationIntent.Pop
            updatedNavController.queue(intent)
        },
        expanded = !LocalNavigationNodeFinishing.current,
    ) {
        val scrollState = rememberScrollState()
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Column(
            modifier = Modifier,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
            ) {
                if (title != null) {
                    Spacer(
                        modifier = Modifier
                            .height(24.dp),
                    )
                    val centerAlign = icon != null
                    Column(
                        modifier = Modifier
                            .padding(horizontal = Dimens.horizontalPadding)
                            .fillMaxWidth(),
                        horizontalAlignment = if (centerAlign) {
                            Alignment.CenterHorizontally
                        } else {
                            Alignment.Start
                        },
                    ) {
                        if (icon != null) {
                            CompositionLocalProvider(
                                LocalContentColor provides MaterialTheme.colorScheme.secondary,
                            ) {
                                icon()
                            }
                            Spacer(
                                modifier = Modifier
                                    .height(16.dp),
                            )
                        }
                        ProvideTextStyle(
                            MaterialTheme.typography.titleLarge
                                .copy(
                                    textAlign = if (centerAlign) {
                                        TextAlign.Center
                                    } else {
                                        TextAlign.Start
                                    },
                                ),
                        ) {
                            title()
                        }
                        Spacer(
                            modifier = Modifier
                                .height(16.dp),
                        )
                    }
                }
                if (content != null) {
                    Box {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (contentScrollable) {
                                        Modifier
                                            .verticalScroll(scrollState)
                                    } else {
                                        Modifier
                                    },
                                )
                                .then(
                                    if (title == null) {
                                        Modifier
                                            .padding(top = 24.dp)
                                    } else {
                                        Modifier
                                    },
                                ),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            CompositionLocalProvider(
                                LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                            ) {
                                content()
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth(),
                            visible = scrollState.canScrollBackward && contentScrollable && title != null,
                        ) {
                            HorizontalDivider(transparency = false)
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(),
                            visible = scrollState.canScrollForward && contentScrollable,
                        ) {
                            HorizontalDivider(transparency = false)
                        }
                    }
                    Spacer(
                        modifier = Modifier
                            .height(16.dp),
                    )
                }
            }
            FlowRow(
                modifier = Modifier
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                    )
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                actions()
            }
            Spacer(
                modifier = Modifier
                    .height(16.dp),
            )
        }
    }
}
