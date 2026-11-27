package com.artemchep.keyguard.feature.masterkdf

import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.platform.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MasterKdfFormValidationTest {
    @Test
    fun `bounds of every field are inclusive`() {
        MasterKdfFormField.entries.forEach { field ->
            assertEquals(
                MasterKdfFieldValidation.Valid(field.min),
                validateMasterKdfField(field, field.min.toString()),
            )
            assertEquals(
                MasterKdfFieldValidation.Valid(field.max),
                validateMasterKdfField(field, field.max.toString()),
            )
            assertEquals(
                MasterKdfFieldValidation.OutOfRange,
                validateMasterKdfField(field, (field.min - 1).toString()),
            )
            assertEquals(
                MasterKdfFieldValidation.OutOfRange,
                validateMasterKdfField(field, (field.max + 1).toString()),
            )
        }
    }

    @Test
    fun `bounds match the model`() {
        assertEquals(2, MasterKdfFormField.ITERATIONS.min)
        assertEquals(100, MasterKdfFormField.ITERATIONS.max)
        assertEquals(16, MasterKdfFormField.MEMORY_MIB.min)
        assertEquals(1024, MasterKdfFormField.MEMORY_MIB.max)
        assertEquals(1, MasterKdfFormField.PARALLELISM.min)
        assertEquals(16, MasterKdfFormField.PARALLELISM.max)
    }

    @Test
    fun `empty text is empty`() {
        val field = MasterKdfFormField.ITERATIONS
        assertEquals(MasterKdfFieldValidation.Empty, validateMasterKdfField(field, ""))
        assertEquals(MasterKdfFieldValidation.Empty, validateMasterKdfField(field, "   "))
    }

    @Test
    fun `non-numeric text is not a number`() {
        val field = MasterKdfFormField.MEMORY_MIB
        assertEquals(MasterKdfFieldValidation.NotANumber, validateMasterKdfField(field, "abc"))
        assertEquals(MasterKdfFieldValidation.NotANumber, validateMasterKdfField(field, "64.5"))
        assertEquals(MasterKdfFieldValidation.NotANumber, validateMasterKdfField(field, "6 4"))
        assertEquals(MasterKdfFieldValidation.NotANumber, validateMasterKdfField(field, "-"))
    }

    @Test
    fun `negative and huge numbers are out of range`() {
        val field = MasterKdfFormField.MEMORY_MIB
        assertEquals(MasterKdfFieldValidation.OutOfRange, validateMasterKdfField(field, "-64"))
        assertEquals(MasterKdfFieldValidation.OutOfRange, validateMasterKdfField(field, "99999999999"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(
            MasterKdfFieldValidation.Valid(64),
            validateMasterKdfField(MasterKdfFormField.MEMORY_MIB, " 64 "),
        )
    }

    @Test
    fun `default texts give the default kdf`() {
        val kdf = MasterKdf.Argon2id.DEFAULT
        assertEquals("3", kdf.iterationsText())
        assertEquals("64", kdf.memoryMibText())
        assertEquals("4", kdf.parallelismText())
        assertEquals(
            kdf,
            masterKdfOrNull(
                iterations = kdf.iterationsText(),
                memoryMib = kdf.memoryMibText(),
                parallelism = kdf.parallelismText(),
            ),
        )
    }

    @Test
    fun `memory is converted from MiB to KiB`() {
        val kdf = masterKdfOrNull(iterations = "5", memoryMib = "128", parallelism = "2")
        assertEquals(
            MasterKdf.Argon2id(iterations = 5, memoryKib = 131072, parallelism = 2),
            kdf,
        )
        assertFalse(kdf == MasterKdf.Argon2id.DEFAULT)
    }

    @Test
    fun `any invalid field gives no kdf`() {
        assertNull(masterKdfOrNull(iterations = "1", memoryMib = "64", parallelism = "4"))
        assertNull(masterKdfOrNull(iterations = "3", memoryMib = "2048", parallelism = "4"))
        assertNull(masterKdfOrNull(iterations = "3", memoryMib = "64", parallelism = ""))
        assertNull(masterKdfOrNull(iterations = "x", memoryMib = "64", parallelism = "4"))
    }

    @Test
    fun `memory warning is shown on iOS only above 64 MiB`() {
        val ios = Platform.Mobile.Ios.Native
        assertFalse(hasMasterKdfMemoryWarning(ios, 64))
        assertTrue(hasMasterKdfMemoryWarning(ios, 65))
        assertFalse(hasMasterKdfMemoryWarning(ios, null))
        assertFalse(hasMasterKdfMemoryWarning(Platform.Desktop.Windows, 1024))
        assertFalse(
            hasMasterKdfMemoryWarning(
                Platform.Mobile.Android(isChromebook = false, isWatch = false, sdk = 36),
                1024,
            ),
        )
    }

    @Test
    fun `seconds are formatted with one decimal`() {
        assertEquals("1.2", formatMasterKdfSeconds(1200.milliseconds))
        assertEquals("1.3", formatMasterKdfSeconds(1250.milliseconds))
        assertEquals("10.0", formatMasterKdfSeconds(10.seconds))
        // Never rounds down to zero.
        assertEquals("0.1", formatMasterKdfSeconds(30.milliseconds))
    }
}
