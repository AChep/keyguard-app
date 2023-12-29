package com.artemchep.keyguard.common.model

data class DFolderTree(
    val folder: DFolder,
    val hierarchy: List<Node>,
) {
    data class Node(
        val name: String,
        val folder: DFolder,
    )
}

data class DFolderTree2<T>(
    val folder: T,
    val hierarchy: List<Node<T>>,
) {
    data class Node<T>(
        val name: String,
        val folder: T,
    )
}
