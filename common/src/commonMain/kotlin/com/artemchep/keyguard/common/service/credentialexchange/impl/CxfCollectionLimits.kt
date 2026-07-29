package com.artemchep.keyguard.common.service.credentialexchange.impl

/**
 * The deepest collection nesting Keyguard emits, and the deepest it preserves
 * on import: 256 nesting levels, where the outermost collection is level 1.
 *
 * Past it a subtree is re-rooted as a top-level collection -- never dropped --
 * so nothing is lost on either side and nothing is counted as a skip. The cap
 * exists to bound the recursive walk on a hostile document; it sits far above
 * any nesting a human-built vault reaches.
 *
 * Both the exporter ([buildCollections]) and the importer
 * ([buildImportFolderPlan]) read this symbol, so the two cannot drift apart.
 */
internal const val CXF_MAX_COLLECTION_DEPTH = 256
