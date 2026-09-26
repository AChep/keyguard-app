package com.artemchep.keyguard.feature.urlblock

import com.artemchep.keyguard.common.model.MatchDetection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlBlockUriValidationTest {
    @Test
    fun malformedExpressionIsRejectedOnlyInRegexMode() {
        assertFalse(isUrlBlockUriValid("[", MatchDetection.RegularExpression))
        assertTrue(isUrlBlockUriValid("https://example.com/[", MatchDetection.Exact))
        assertTrue(isUrlBlockUriValid("example.com", MatchDetection.Domain))
    }

    @Test
    fun validExpressionIsAccepted() {
        assertTrue(isUrlBlockUriValid("^https?://example\\.com/.*$", MatchDetection.RegularExpression))
    }

    @Test
    fun blankUriIsRejectedInEveryMode() {
        MatchDetection.entries.forEach { mode ->
            assertFalse(isUrlBlockUriValid("  ", mode))
        }
    }
}
