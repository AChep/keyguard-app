package com.artemchep.keyguard.util.planeta.internal

import kotlin.math.PI

internal class PlanetaRandom(
    seed: Long,
) {
    private var state = seed

    fun nextLong(): Long {
        state += -7046029254386353131L
        var value = state
        value = (value xor (value ushr 30)) * -4658895280553007687L
        value = (value xor (value ushr 27)) * -7723592293110705685L
        return value xor (value ushr 31)
    }

    fun nextFloat(): Float =
        (nextLong() ushr 40).toFloat() / (1L shl 24).toFloat()

    fun nextFloat(
        min: Float,
        max: Float,
    ): Float = min + (max - min) * nextFloat()

    fun nextInt(
        min: Int,
        maxExclusive: Int,
    ): Int {
        require(maxExclusive > min)
        return min + (nextFloat() * (maxExclusive - min)).toInt().coerceIn(0, maxExclusive - min - 1)
    }

    fun nextInt(maxExclusive: Int): Int =
        nextInt(0, maxExclusive)

    fun nextBoolean(): Boolean =
        nextLong() and 1L == 1L

    fun nextRadians(): Float =
        nextFloat(0f, (PI * 2.0).toFloat())

    fun <T> pick(
        first: T,
        second: T,
    ): T = if (nextBoolean()) first else second
}
