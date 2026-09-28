package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.feature.navigation.NavigationIntent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddFormNavigationTest {
    @Test
    fun successfulSaveClosesOnlyItsOwnForm() {
        val intent = NavigationIntent.Composite(
            listOf(NavigationIntent.PopById("cipher_add", exclusive = false)),
        )
        assertTrue(intent.closesAddForm("cipher_add"))
        assertFalse(intent.closesAddForm("send_add"))
    }

    @Test
    fun pickerDismissalAndPopThatRetainsTheEditorDoNotCloseIt() {
        assertFalse(NavigationIntent.Pop.closesAddForm("cipher_add"))
        assertFalse(NavigationIntent.PopById("picker", exclusive = false).closesAddForm("cipher_add"))
        assertFalse(NavigationIntent.PopById("cipher_add", exclusive = true).closesAddForm("cipher_add"))
    }

    @Test
    fun nestedCompositePreservesSaveCompletion() {
        val intent = NavigationIntent.Composite(
            listOf(NavigationIntent.Composite(
                listOf(NavigationIntent.PopById("send_add", exclusive = false)),
            )),
        )
        assertTrue(intent.closesAddForm("send_add"))
    }
}
