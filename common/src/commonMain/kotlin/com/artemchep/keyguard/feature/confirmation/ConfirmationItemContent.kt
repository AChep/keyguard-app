package com.artemchep.keyguard.feature.confirmation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.dialog.DialogFormKeyboard

data class ConfirmationItemRenderers(
    val booleanItem: @Composable (Modifier, ConfirmationState.Item.BooleanItem) -> Unit,
    val stringItem: @Composable (Modifier, ConfirmationState.Item.StringItem, DialogFormKeyboard) -> Unit,
    val enumItem: @Composable (Modifier, ConfirmationState.Item.EnumItem) -> Unit,
    val fileItem: @Composable (Modifier, ConfirmationState.Item.FileItem) -> Unit,
)

@Composable
fun ConfirmationItemContent(
    modifier: Modifier = Modifier,
    item: ConfirmationState.Item,
    renderers: ConfirmationItemRenderers,
    keyboard: DialogFormKeyboard,
) = when (item) {
    is ConfirmationState.Item.BooleanItem -> renderers.booleanItem(modifier.then(keyboard.target(item.key)), item)
    is ConfirmationState.Item.StringItem -> renderers.stringItem(modifier, item, keyboard)
    is ConfirmationState.Item.EnumItem -> renderers.enumItem(modifier.then(keyboard.target(item.key)), item)
    is ConfirmationState.Item.FileItem -> renderers.fileItem(modifier.then(keyboard.target(item.key)), item)
}
