package com.artemchep.keyguard.android.autofill.v2.cluster

import com.artemchep.keyguard.android.autofill.v2.model.NormalizedStructureV2

/**
 * Stage 2 of the v2 parser pipeline: clustering.
 *
 * Groups the flat list of extracted fields into [FieldCluster]s based on
 * form boundaries and cluster IDs, then heuristically classifies each
 * cluster's [ClusterType] (e.g. AUTH, PAYMENT, ADDRESS).
 */
interface StructureClusterBuilderV2 {
    fun build(extracted: NormalizedStructureV2): NormalizedStructureV2
}
