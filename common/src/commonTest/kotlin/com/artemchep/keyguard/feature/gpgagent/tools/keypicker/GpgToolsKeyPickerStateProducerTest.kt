package com.artemchep.keyguard.feature.gpgagent.tools.keypicker

import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.gpgagent.tools.GpgToolsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpgToolsKeyPickerStateProducerTest {
    private val alice = key("alice", "Alice", "ABCD 1234 EFGH 5678")
    private val bob = key("bob", "Bob", "9876 5432 1010 1212")

    @Test
    fun `recipient edits are staged and cancel discards them`() = runTest {
        val picker = Picker(selectedIds = setOf(alice.id))
        picker.state().items.single { it.key.id == bob.id }.onSelect()
        assertTrue(picker.confirmations.isEmpty())
        val edited = picker.state()
        assertTrue(edited.items.all { it.selected })
        edited.onDeny!!.invoke()
        // A callback from the closing dialog cannot commit a discarded draft.
        edited.onConfirm!!.invoke()
        assertTrue(picker.confirmations.isEmpty())
        assertEquals(1, picker.dismissals)
    }

    @Test
    fun `done commits every selected recipient once including hidden search results`() = runTest {
        val picker = Picker(selectedIds = setOf(alice.id))
        picker.state().query.onChange!!.invoke("bob")
        val searched = picker.state()
        assertEquals(listOf(bob.id), searched.items.map { it.key.id })
        searched.items.single().onSelect()
        val confirm = picker.state().onConfirm!!
        confirm()
        confirm()
        assertEquals(listOf(setOf(alice.id, bob.id)), picker.confirmations)
        assertEquals(1, picker.dismissals)
    }

    @Test
    fun `recipients can all be removed`() = runTest {
        val picker = Picker(selectedIds = setOf(alice.id))
        picker.state().items.single { it.selected }.onSelect()
        picker.state().onConfirm!!.invoke()
        assertEquals(listOf(emptySet()), picker.confirmations)
    }

    @Test
    fun `signing replaces the selection and cannot confirm without a key`() = runTest {
        val picker = Picker(mode = GpgToolsKeyPickerRoute.Mode.Signing)
        assertNull(picker.state().onConfirm)
        assertNull(picker.state().onSelectNone)
        picker.state().items.first().onSelect()
        picker.state().items.last().onSelect()
        val state = picker.state()
        assertEquals(listOf(bob.id), state.items.filter { it.selected }.map { it.key.id })
        state.onConfirm!!.invoke()
        assertEquals(listOf(setOf(bob.id)), picker.confirmations)
    }

    @Test
    fun `optional signing can clear the signing key even while searching`() = runTest {
        val picker = Picker(
            mode = GpgToolsKeyPickerRoute.Mode.OptionalSigning,
            selectedIds = setOf(alice.id),
        )
        picker.state().query.onChange!!.invoke("no match")
        val state = picker.state()
        assertTrue(state.items.isEmpty())
        state.onSelectNone!!.invoke()
        assertTrue(picker.state().noneSelected)
        picker.state().onConfirm!!.invoke()
        assertEquals(listOf(emptySet()), picker.confirmations)
    }

    @Test
    fun `search matches names and spaced fingerprints without changing order`() {
        val keys = listOf(alice, bob, alice.copy(id = "another-alice"))
        assertEquals(listOf(keys[0], keys[2]), filterGpgToolsKeys(keys, " ALICE "))
        assertEquals(listOf(keys[0], keys[2]), filterGpgToolsKeys(keys, "cd12 34ef"))
        assertEquals(listOf(bob), filterGpgToolsKeys(keys, "9876\t5432"))
        assertEquals(keys, filterGpgToolsKeys(keys, "  "))
        assertTrue(filterGpgToolsKeys(keys, "missing").isEmpty())
        assertTrue(filterGpgToolsKeys(listOf(alice.copy(description = null)), "abcd").isEmpty())
    }

    @Test
    fun `confirmation excludes unavailable keys and rejects invalid signing selections`() {
        val available = setOf(bob.id)
        assertEquals(
            setOf(bob.id),
            confirmedGpgToolsKeySelection(
                GpgToolsKeyPickerRoute.Mode.Recipients, available, setOf(alice.id, bob.id),
            ),
        )
        assertNull(
            confirmedGpgToolsKeySelection(GpgToolsKeyPickerRoute.Mode.Signing, available, setOf(alice.id)),
        )
        assertEquals(
            emptySet(),
            confirmedGpgToolsKeySelection(GpgToolsKeyPickerRoute.Mode.OptionalSigning, available, setOf(alice.id)),
        )
        assertNull(
            confirmedGpgToolsKeySelection(
                GpgToolsKeyPickerRoute.Mode.Signing, setOf(alice.id, bob.id), setOf(alice.id, bob.id),
            ),
        )
    }

    @Test
    fun `empty key lists allow clearing recipients or optional signing only`() = runTest {
        for (mode in GpgToolsKeyPickerRoute.Mode.entries) {
            val picker = Picker(mode = mode, keys = emptyList())
            val state = picker.state()
            assertTrue(state.items.isEmpty())
            if (mode == GpgToolsKeyPickerRoute.Mode.Signing) {
                assertNull(state.onConfirm)
            } else {
                assertNotNull(state.onConfirm)
            }
        }
    }

    private inner class Picker(
        mode: GpgToolsKeyPickerRoute.Mode = GpgToolsKeyPickerRoute.Mode.Recipients,
        selectedIds: Set<String> = emptySet(),
        keys: List<GpgToolsState.KeyItem> = listOf(alice, bob),
    ) {
        val confirmations = mutableListOf<Set<String>>()
        var dismissals = 0
        private val flow = gpgToolsKeyPickerStateFlow(
            args = GpgToolsKeyPickerRoute.Args(keys, selectedIds, mode),
            selectedIdsSink = MutableStateFlow(selectedIds),
            queryHandle = TextFieldHandle(MutableStateFlow(TextCell(""))),
            onConfirm = { confirmations += it },
            onDismiss = { dismissals++ },
        )

        suspend fun state() = flow.first()
    }

    private fun key(id: String, title: String, fingerprint: String) = GpgToolsState.KeyItem(
        id = id,
        title = title,
        description = fingerprint,
        canSign = true,
        canDecrypt = true,
        publicKeyAvailable = true,
    )
}
