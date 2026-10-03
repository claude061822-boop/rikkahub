package me.rerere.ai.provider.providers.openai

import me.rerere.ai.ui.StreamChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import kotlin.uuid.Uuid

class GatewayCandidateStreamReceiptTest {
    private fun receipt() = GatewayCandidateStreamReceipt(
        StreamChunk.GatewayCandidate("candidate-1", "https://gateway.example/select", "shared", Uuid.random()),
    )

    @Test fun `final reply is selectable only after done and committed receipt`() {
        val receipt = receipt()
        assertThrows(IOException::class.java) { receipt.commit("candidate-1") }
        receipt.observe(emptyList(), true)
        assertEquals("candidate-1", receipt.commit("candidate-1").candidateId)
        receipt.verifyClosed()
        assertThrows(IOException::class.java) { receipt.commit("candidate-1") }
    }

    @Test fun `interrupted or failed registration never confirms candidate`() {
        val interrupted = receipt()
        interrupted.observe(emptyList(), false)
        assertThrows(IOException::class.java) { interrupted.verifyClosed() }
        val failed = receipt()
        failed.observe(emptyList(), true)
        assertThrows(IOException::class.java) { failed.verifyClosed() }
        assertThrows(IOException::class.java) { failed.commit("wrong-candidate") }
    }

    @Test fun `tool stage completes without final candidate receipt`() {
        val receipt = receipt()
        receipt.observe(listOf(StreamChunk.ToolCallStart("call-1")), true)
        receipt.verifyClosed()
        assertThrows(IOException::class.java) { receipt.commit("candidate-1") }
    }
}
