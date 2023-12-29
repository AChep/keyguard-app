package com.artemchep.keyguard.android.downloader

interface NotificationIdPool {
    companion object {
        fun sequential(
            start: Int,
        ): NotificationIdPool = NotificationIdPoolImpl(
            offset = start,
        )

        fun sequential(
            start: Int,
            endExclusive: Int,
        ): NotificationIdPool = NotificationIdPoolSimple(
            start = start,
            endExclusive = endExclusive,
        )
    }

    fun obtainId(): Int

    fun releaseId(id: Int)
}

inline fun <T> NotificationIdPool.withId(
    block: (Int) -> T,
) = kotlin.run {
    val id = obtainId()
    try {
        block(id)
    } finally {
        releaseId(id)
    }
}

private class NotificationIdPoolSimple(
    private val start: Int,
    private val endExclusive: Int,
) : NotificationIdPool {
    private var value = start

    override fun obtainId(): Int = synchronized(this) {
        val newValue = value + 1
        // Loop around in case of integer
        // overflow.
        value = if (newValue >= endExclusive) start else newValue
        value
    }

    override fun releaseId(id: Int) {
        // Do nothing.
    }
}

private class NotificationIdPoolImpl(
    private val offset: Int,
) : NotificationIdPool {
    private val pool = mutableListOf<Int>()

    override fun obtainId(): Int = pool.obtainId(offset = offset)

    override fun releaseId(id: Int) = pool.releaseId(id = id)
}

private fun MutableList<Int>.obtainId(
    offset: Int,
): Int = synchronized(this) {
    val ids = generateSequence(offset) { it + 1 }.iterator()
    var index = 0
    while (true) {
        val id = ids.next()
        val idFromPool = this.getOrNull(index)
        if (idFromPool != id) {
            this.add(index, id)
            return@synchronized id
        }
        index++
    }
    @Suppress("UNREACHABLE_CODE")
    error("Unreachable statement!")
}

private fun MutableList<Int>.releaseId(
    id: Int,
) = synchronized(this) {
    val removed = this.remove(id)
    require(removed)
}
