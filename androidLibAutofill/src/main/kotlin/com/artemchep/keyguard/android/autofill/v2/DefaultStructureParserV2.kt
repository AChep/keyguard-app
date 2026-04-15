package com.artemchep.keyguard.android.autofill.v2

import android.app.assist.AssistStructure
import com.artemchep.keyguard.android.autofill.v2.api.StructureParserV2
import com.artemchep.keyguard.android.autofill.v2.cluster.DefaultStructureClusterBuilderV2
import com.artemchep.keyguard.android.autofill.v2.cluster.StructureClusterBuilderV2
import com.artemchep.keyguard.android.autofill.v2.extract.DefaultStructureExtractorV2
import com.artemchep.keyguard.android.autofill.v2.extract.StructureExtractorV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseDebugResultV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseOptions
import com.artemchep.keyguard.android.autofill.v2.model.ParseResultV2
import com.artemchep.keyguard.android.autofill.v2.resolve.DefaultStructureResolverV2
import com.artemchep.keyguard.android.autofill.v2.resolve.StructureResolverV2

/**
 * Default orchestrator for the three-stage v2 autofill parser pipeline:
 * **extract** (view-tree traversal) → **cluster** (field grouping and cluster-type
 * classification) → **resolve** (Bayesian field-type and form-intent resolution).
 *
 * Each stage is pluggable via constructor parameters for testing.
 */
class DefaultStructureParserV2(
    private val extractor: StructureExtractorV2 = DefaultStructureExtractorV2(),
    private val clusterBuilder: StructureClusterBuilderV2 = DefaultStructureClusterBuilderV2(),
    private val resolver: StructureResolverV2 = DefaultStructureResolverV2(),
) : StructureParserV2 {
    override fun parse(
        assistStructure: AssistStructure,
        options: ParseOptions,
    ): ParseResultV2 =
        parseDebug(
            assistStructure = assistStructure,
            options = options,
        ).result

    override fun parseDebug(
        assistStructure: AssistStructure,
        options: ParseOptions,
    ): ParseDebugResultV2 {
        val extracted =
            extractor.extract(
                assistStructure = assistStructure,
                options = options,
            )
        val clustered = clusterBuilder.build(extracted)
        return resolver.resolve(clustered, options)
    }
}
