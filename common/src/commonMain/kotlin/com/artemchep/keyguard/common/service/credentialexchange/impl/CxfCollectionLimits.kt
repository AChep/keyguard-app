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
 * ([cxfCollectionFolders]) read this symbol, so the two cannot drift apart.
 */
internal const val CXF_MAX_COLLECTION_DEPTH = 256

/**
 * The deepest JSON nesting an imported CXF document may have. Each collection
 * level costs two (the collection object and its `subCollections` array), so
 * this holds a tree of [CXF_MAX_COLLECTION_DEPTH] levels plus the document
 * envelope and some re-rooted overflow.
 *
 * Deeper documents fail the parse before anything walks them: the collection
 * decode and placement recurse once per level, and Kotlin/Native cannot catch
 * the stack overflow a hostile chain would cause.
 */
internal const val CXF_MAX_JSON_DEPTH = 2 * CXF_MAX_COLLECTION_DEPTH + 64
