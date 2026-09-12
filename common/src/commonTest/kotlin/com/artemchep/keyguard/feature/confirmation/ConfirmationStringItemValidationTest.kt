package com.artemchep.keyguard.feature.confirmation

import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_invalid_regex
import com.artemchep.keyguard.res.error_must_not_be_blank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfirmationStringItemValidationTest {
    private val regexItem = ConfirmationRoute.Args.Item.StringItem(
        key = "regex",
        title = "Regex",
        type = ConfirmationRoute.Args.Item.StringItem.Type.Regex,
        canBeEmpty = false,
    )

    @Test
    fun malformedRegexHasValidationError() {
        assertEquals(
            Res.string.error_invalid_regex,
            confirmationStringItemError(regexItem, "["),
        )
    }

    @Test
    fun validRegexAndOptionalEmptyRegexAreAccepted() {
        assertNull(confirmationStringItemError(regexItem, "^https://.*$"))
        assertNull(confirmationStringItemError(regexItem.copy(canBeEmpty = true), ""))
    }

    @Test
    fun requiredRegexRetainsBlankValidation() {
        assertEquals(
            Res.string.error_must_not_be_blank,
            confirmationStringItemError(regexItem, "  "),
        )
    }

    @Test
    fun nonRegexFieldsAcceptRegexMetacharacters() {
        assertNull(
            confirmationStringItemError(
                regexItem.copy(type = ConfirmationRoute.Args.Item.StringItem.Type.Text),
                "[",
            ),
        )
    }
}
