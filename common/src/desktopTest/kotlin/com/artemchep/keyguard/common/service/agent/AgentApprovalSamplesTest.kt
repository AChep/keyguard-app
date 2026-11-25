package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.feature.agent.sample.ApprovalPromptSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AgentApprovalSamplesTest {
    @Test
    fun `a shown sample is pending until it is approved`() = runTest {
        val samples = AgentApprovalSamples()
        val result = async { samples.show(ApprovalPromptSample.CliRun::build) }
        runCurrent()

        val prompt = samples.pending.value.single()
        assertIs<AgentPrompt.Cli>(prompt.prompt)
        assertTrue(prompt.id.startsWith("sample-"))

        assertTrue(samples.respond(prompt.id, approved = true))
        assertEquals(AgentApprovalBroker.Result.Approved, result.await())
        assertTrue(samples.pending.value.isEmpty())
        assertFalse(samples.respond(prompt.id, approved = true), "A sample can be answered only while it is pending")
    }

    @Test
    fun `a shown sample can be denied`() = runTest {
        val samples = AgentApprovalSamples()
        val result = async { samples.show(ApprovalPromptSample.SshKnownCaller::build) }
        runCurrent()

        val prompt = samples.pending.value.single()
        assertIs<AgentPrompt.Ssh>(prompt.prompt)

        assertTrue(samples.respond(prompt.id, approved = false))
        assertEquals(AgentApprovalBroker.Result.Denied, result.await())
        assertTrue(samples.pending.value.isEmpty())
    }

    @Test
    fun `every sample shows under its own id`() = runTest {
        val samples = AgentApprovalSamples()
        val results = ApprovalPromptSample.entries.map { sample ->
            async { samples.show(sample::build) }
        }
        runCurrent()

        val prompts = samples.pending.value
        assertEquals(ApprovalPromptSample.entries.size, prompts.map { it.id }.toSet().size)
        prompts.forEach { prompt ->
            assertTrue(samples.respond(prompt.id, approved = false))
        }
        results.forEach { assertEquals(AgentApprovalBroker.Result.Denied, it.await()) }
    }
}
