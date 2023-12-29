package com.artemchep.keyguard.ui

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

@Composable
fun InteractionSource.collectIsInteractedWith(): Boolean {
    var isInteractedWith by remember(this) {
        mutableStateOf(false)
    }

    LaunchedEffect(this) {
        val pressInteractions = mutableListOf<PressInteraction.Press>()
        val dragInteractions = mutableListOf<DragInteraction.Start>()
        interactions
            .onEach { interaction: Interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressInteractions.add(interaction)
                    is PressInteraction.Release -> pressInteractions.remove(interaction.press)
                    is PressInteraction.Cancel -> pressInteractions.remove(interaction.press)
                    is DragInteraction.Start -> dragInteractions.add(interaction)
                    is DragInteraction.Stop -> dragInteractions.remove(interaction.start)
                    is DragInteraction.Cancel -> dragInteractions.remove(interaction.start)
                }
                val userIsInteracting = pressInteractions.isNotEmpty() ||
                        dragInteractions.isNotEmpty()
                isInteractedWith = userIsInteracting
            }
            .collect()
    }

    return isInteractedWith
}
