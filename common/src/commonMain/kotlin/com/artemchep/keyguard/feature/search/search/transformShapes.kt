package com.artemchep.keyguard.feature.search.search

import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.getShapeState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun <T> Flow<Pair<List<T>, Int>>.mapShape(
) = this
    .map { (items, rev) ->
        val shapedItems = items
            .mapIndexed { index, item ->
                val p = item as? GroupableShapeItem<T>
                if (p != null) {
                    val shapeState = getShapeState(
                        list = items,
                        index = index,
                        predicate = { el, offset ->
                            el is GroupableShapeItem<*>
                        },
                    )
                    p.withShape(shapeState)
                } else {
                    item
                }
            }
        shapedItems to rev
    }

fun <T> Flow<List<T>>.mapShape(
) = this
    .map { items ->
        val shapedItems = items
            .mapIndexed { index, item ->
                val p = item as? GroupableShapeItem<T>
                if (p != null) {
                    val shapeState = getShapeState(
                        list = items,
                        index = index,
                        predicate = { el, offset ->
                            el is GroupableShapeItem<*>
                        },
                    )
                    p.withShape(shapeState)
                } else {
                    item
                }
            }
        shapedItems
    }

inline fun <T, R : T> Flow<Pair<List<T>, Int>>.mapShape(
    crossinline cast: (T) -> R?,
    crossinline transform: (R, Int) -> R,
) = this
    .map { (items, rev) ->
        val shapedItems = items
            .mapIndexed { index, item ->
                val p = cast(item)
                if (p != null) {
                    val shapeState = getShapeState(
                        list = items,
                        index = index,
                        predicate = { el, offset ->
                            cast(el) != null
                        },
                    )
                    transform(p, shapeState)
                } else {
                    item
                }
            }
        shapedItems to rev
    }
