@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package com.artemchep.keyguard.feature.home.vault.screen

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationComposeHarness
import com.artemchep.keyguard.feature.navigation.press
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.focusedTexts
import kotlin.test.assertTrue

// The items of the keyboard scene tests and their fields, see withVaultList.

internal const val USERNAME = "Username"

internal const val PHONE = "Phone"

/** An item opened on top, it has the default fields. */
internal const val OTHER_ITEM = "other"

/** A field with the [title] and the [itemFieldValue], its menu has one action. */
internal fun itemField(title: String) = VaultViewItem.Value(
    id = title,
    title = title,
    value = itemFieldValue(title),
    dropdown = listOf(
        FlatItemAction(title = TextHolder.Value(itemFieldAction(title))),
    ),
)

internal fun itemFieldValue(title: String) = "$title value"

/** The one action in the menu of the [itemField] of the [title]. */
internal fun itemFieldAction(title: String) = "Copy $title"

/** The texts of the [itemField] of the [title], as a focused node has them. */
internal fun itemFieldTexts(title: String) = listOf(title, itemFieldValue(title))

/** Opens the item of the second row with Enter, in keyboard input mode. */
internal fun NavigationComposeHarness.openSecondItem() {
    focusSecondRow()
    press(Key.Enter)
}

/** Asserts that the texts of the focused node have the [text]. */
internal fun NavigationComposeHarness.assertFocusedHasText(text: String, message: String? = null) {
    val texts = scene.focusedTexts()
    assertTrue(text in texts, listOfNotNull(message, "$texts").joinToString(": "))
}
