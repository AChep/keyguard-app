package com.artemchep.keyguard.android.autofill.v2.api

import android.app.assist.AssistStructure
import com.artemchep.keyguard.android.autofill.v2.model.ParseDebugResultV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseOptions
import com.artemchep.keyguard.android.autofill.v2.model.ParseResultV2

/**
 * Top-level entry point for the v2 autofill parser pipeline.
 *
 * Implementations accept an Android [AssistStructure] and return classified
 * field types and form intents. Use [parse] for production autofill and
 * [parseDebug] when you need the full set of intermediate proposals for
 * diagnostics or testing.
 */
interface StructureParserV2 {
    /** Parses the [assistStructure] and returns the final field-type / form-intent map. */
    fun parse(
        assistStructure: AssistStructure,
        options: ParseOptions = ParseOptions(),
    ): ParseResultV2

    /**
     * Same as [parse], but also returns all intermediate analyzer proposals
     * and debug notes for inspection.
     */
    fun parseDebug(
        assistStructure: AssistStructure,
        options: ParseOptions = ParseOptions(),
    ): ParseDebugResultV2
}
