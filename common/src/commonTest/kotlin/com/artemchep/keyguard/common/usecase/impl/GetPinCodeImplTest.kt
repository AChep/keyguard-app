package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.bindBlocking
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.test.TestCryptoGenerator
import kotlin.test.Test
import kotlin.test.assertEquals

class GetPinCodeImplTest {
    @Test
    fun `pin generator rejects common pin when search converges on final candidate`() {
        val cryptoGenerator = SequentialCryptoGenerator(9999, 2346)
        val useCase = GetPinCodeImpl(
            cryptoGenerator = cryptoGenerator,
        )

        val pin = useCase(
            config = PasswordGeneratorConfig.PinCode(length = 4),
        ).bindBlocking()

        assertEquals("2346", pin)
        assertEquals(2, cryptoGenerator.randomCalls)
    }
}

private class SequentialCryptoGenerator(
    vararg values: Int,
) : TestCryptoGenerator() {
    private val values = values.toMutableList()

    var randomCalls: Int = 0
        private set

    override fun random(range: IntRange): Int {
        val value = values.removeAt(0)
        require(value in range)
        randomCalls += 1
        return value
    }
}
