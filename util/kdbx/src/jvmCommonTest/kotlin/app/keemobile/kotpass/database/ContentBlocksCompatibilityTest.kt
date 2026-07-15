package app.keemobile.kotpass.database

import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

class ContentBlocksCompatibilityTest {
    @Test
    fun writesKdbx4ContentBlockCompatibilityVector() {
        val buffer = Buffer()

        ContentBlocks.writeContentBlocksVer4x(
            sink = buffer,
            contentData = "payload".encodeToByteArray(),
            masterSeed = ByteArray(32) { index -> index.toByte() },
            transformedKey = ByteArray(32) { index -> (index + 32).toByte() },
        )

        val expected =
            (
                "06dcc30a4bd1774fbefe821281575ffaaec764cadeaa37bdeff17cec08193daa" +
                    "070000007061796c6f6164" +
                    "aefcc91d8fb70ad4625a68e65c9844b8198b2c9631bcb0f43cbf8d2f36c2407d" +
                    "00000000"
            ).decodeHex()
        assertContentEquals(expected, buffer.readByteArray())
    }

    private fun String.decodeHex(): ByteArray =
        chunked(2)
            .map { byte -> byte.toInt(16).toByte() }
            .toByteArray()
}
