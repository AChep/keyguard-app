package com.artemchep.keyguard.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusEvent
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bringIntoView() = this
    .composed {
        val wasFocusedState = remember { mutableStateOf(false) }
        val bringIntoViewRequester = remember { BringIntoViewRequester() }
        val coroutineScope = rememberCoroutineScope()
        this
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusEvent { focusState ->
                val isFocused = focusState.hasFocus
                val wasFocused = wasFocusedState.value
                if (wasFocused != isFocused) {
                    wasFocusedState.value = isFocused
                    if (isFocused) {
                        coroutineScope.launch {
                            bringIntoViewRequester.bringIntoView()
                        }
                    }
                }
            }
    }
