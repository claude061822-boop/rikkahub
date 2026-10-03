package me.rerere.ai.provider.providers.openai

import me.rerere.ai.ui.StreamChunk
import java.io.IOException

/** A response header reserves an identity; only the terminal Gateway event commits it. */
internal class GatewayCandidateStreamReceipt(
    private val candidate: StreamChunk.GatewayCandidate,
) {
    private var completed = false
    private var sawToolCall = false
    private var committed = false

    fun observe(chunks: List<StreamChunk>, done: Boolean) {
        if (chunks.any { it is StreamChunk.ToolCallStart }) sawToolCall = true
        if (done) completed = true
    }

    fun commit(candidateId: String): StreamChunk.GatewayCandidate {
        if (!completed || sawToolCall || candidate.candidateId != candidateId || committed) {
            throw IOException("Invalid Gateway candidate receipt")
        }
        committed = true
        return candidate
    }

    fun verifyClosed() {
        if (!completed || (!sawToolCall && !committed)) {
            throw IOException("Gateway candidate receipt was not committed")
        }
    }
}
