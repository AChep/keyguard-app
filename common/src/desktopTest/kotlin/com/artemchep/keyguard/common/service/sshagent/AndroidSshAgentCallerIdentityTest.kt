package com.artemchep.keyguard.common.service.sshagent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class AndroidSshAgentCallerIdentityTest {
    @Test
    fun `buildAndroidSshAgentCallerIdentity returns null when no fields are provided`() {
        assertNull(buildAndroidSshAgentCallerIdentity())
    }

    @Test
    fun `buildAndroidSshAgentCallerIdentity preserves partial caller metadata`() {
        val caller = buildAndroidSshAgentCallerIdentity(
            pid = 123,
            uid = 456,
            gid = 789,
            processName = "termux",
            executablePath = "/data/data/com.termux/files/usr/bin/ssh",
        )

        assertEquals(123, caller?.pid)
        assertEquals(456, caller?.uid)
        assertEquals(789, caller?.gid)
        assertEquals("termux", caller?.processName)
        assertEquals("/data/data/com.termux/files/usr/bin/ssh", caller?.executablePath)
    }

    @Test
    fun `buildAndroidSshAgentCallerIdentity preserves app metadata`() {
        val caller = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
        )

        assertEquals("Termux", caller?.appName)
        assertEquals("com.termux", caller?.appBundlePath)
    }

    @Test
    fun `mergeAndroidSshAgentCallerIdentity overlays app metadata onto existing caller`() {
        val caller = buildAndroidSshAgentCallerIdentity(
            pid = 123,
            uid = 456,
            gid = 789,
            processName = "ssh",
            executablePath = "/data/data/com.termux/files/usr/bin/ssh",
        )
        val senderAppInfo = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
        )

        val merged = mergeAndroidSshAgentCallerIdentity(caller, senderAppInfo)

        assertNotNull(merged)
        assertEquals(123, merged.pid)
        assertEquals(456, merged.uid)
        assertEquals(789, merged.gid)
        assertEquals("ssh", merged.processName)
        assertEquals("/data/data/com.termux/files/usr/bin/ssh", merged.executablePath)
        assertEquals("Termux", merged.appName)
        assertEquals("com.termux", merged.appBundlePath)
    }

    @Test
    fun `mergeAndroidSshAgentCallerIdentity creates app only caller when request caller is missing`() {
        val senderAppInfo = buildAndroidSshAgentCallerIdentity(
            appName = "Termux",
            appBundlePath = "com.termux",
        )

        val merged = mergeAndroidSshAgentCallerIdentity(
            caller = null,
            senderAppInfo = senderAppInfo,
        )

        assertEquals("Termux", merged?.appName)
        assertEquals("com.termux", merged?.appBundlePath)
        assertEquals(0, merged?.pid)
        assertEquals("", merged?.processName)
    }
}
