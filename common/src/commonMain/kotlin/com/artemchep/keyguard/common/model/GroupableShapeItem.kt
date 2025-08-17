package com.artemchep.keyguard.common.model

interface GroupableShapeItem<T> {
    fun withShape(shape: Int): T
}
