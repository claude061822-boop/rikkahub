package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/** Real OkHttp TCP/SSE against the loopback Gateway fixture; no production endpoint. */
class IsolatedGatewaySseIntegrationTest {
    private val origin = System.getenv("ISOLATED_GATEWAY_URL") ?: "http://127.0.0.1:0"
    private val conversationId = UUID.randomUUID().toString()
    private val sessionId = "ai-${UUID.randomUUID()}"
    private val faultSession = "fault-${UUID.randomUUID()}"
    private val turnId = "user-A-${UUID.randomUUID()}"
    private val http = OkHttpClient()
    private val provider = ProviderSetting.OpenAI(apiKey = "gateway-token", baseUrl = "$origin/v1")
    private val api = ChatCompletionsAPI(http, KeyRoulette.default())

    @Before fun requireLoopbackGateway() {
        assumeTrue("opt-in isolated Gateway test", System.getenv("ISOLATED_GATEWAY_URL") != null)
        require(origin.toHttpUrl().host == "127.0.0.1" && origin.toHttpUrl().scheme == "http") {
            "Only a loopback Gateway is allowed"
        }
        assertEquals(200, post("/__test/reset-output", "{}").first)
    }

    private fun params(turn: String = turnId, session: String = sessionId) = TextGenerationParams(
        model = Model(modelId = "shared-model"), sessionId = conversationId, turnId = turn,
        customHeaders = listOf(CustomHeader("X-Ombre-Session-Id", session)),
    )

    private fun post(path: String, payload: String): Pair<Int, String> {
        val request = Request.Builder().url(origin + path)
            .post(payload.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    private fun inspect(): String {
        http.newCall(Request.Builder().url("$origin/__test/inspect?session=$sessionId&conversation=$conversationId").build())
            .execute().use { return it.body!!.string() }
    }

    private fun queue(text: String, extra: String = "") {
        assertEquals(200, post("/__test/enqueue", "{\"text\":\"$text\"$extra}").first)
    }

    private suspend fun streamed(text: String): StreamChunk.GatewayCandidate {
        queue(text)
        val chunks = api.streamText(provider, listOf(UIMessage.user("question A")), params()).toList()
        return chunks.filterIsInstance<StreamChunk.GatewayCandidate>().single()
    }

    @Test fun realHttpSseCandidateLifecycleAndSelection() = runBlocking {
        val first = streamed("B1")
        assertTrue(first.candidateId.isNotBlank())
        api.selectGatewayCandidate(provider, conversationId, first.sessionId,
            first.candidateId, first.selectionUrl, 0)
        val second = streamed("B2")
        api.selectGatewayCandidate(provider, conversationId, second.sessionId,
            second.candidateId, second.selectionUrl, 1)
        val third = streamed("B3")
        api.selectGatewayCandidate(provider, conversationId, third.sessionId,
            third.candidateId, third.selectionUrl, 2)
        api.selectGatewayCandidate(provider, conversationId, first.sessionId,
            first.candidateId, first.selectionUrl, 3)
        val state = inspect()
        assertTrue(state, state.contains("\"canonical\":\"B1\""))
        assertTrue(state, state.contains("\"projection\":[\"B1\"]"))

        // Duplicate acknowledgment is safe; an older revision cannot replace B1.
        api.selectGatewayCandidate(provider, conversationId, first.sessionId,
            first.candidateId, first.selectionUrl, 3)
        val stale = runCatching { api.selectGatewayCandidate(provider, conversationId, third.sessionId,
            third.candidateId, third.selectionUrl, 2) }.exceptionOrNull()
        assertTrue(stale is IllegalStateException && stale.message.orEmpty().contains("HTTP 409"))

        queue("answer C")
        val next = api.streamText(provider,
            listOf(UIMessage.user("question A"), UIMessage.assistant("B1"), UIMessage.user("question C")),
            params("user-message-C")).toList()
        assertEquals(1, next.filterIsInstance<StreamChunk.GatewayCandidate>().size)
        assertTrue(inspect().contains("\"continuity\""))
    }

    @Test fun receiptFailureAndToolStageDoNotYieldSelectableId() = runBlocking {
        queue("failure", ",\"fail_receipt\":true")
        val observed = mutableListOf<StreamChunk>()
        val failed = runCatching {
            api.streamText(provider, listOf(UIMessage.user("failure")), params("fault-turn", faultSession))
                .collect { observed += it }
        }
        assertTrue(failed.isFailure)
        assertFalse(observed.any { it is StreamChunk.GatewayCandidate })

        queue("truncated", ",\"truncate\":true")
        observed.clear()
        val truncated = runCatching {
            api.streamText(provider, listOf(UIMessage.user("truncated")), params("cut-turn", faultSession))
                .collect { observed += it }
        }
        assertTrue(truncated.isFailure)
        assertFalse(observed.any { it is StreamChunk.GatewayCandidate })
        queue("working", ",\"tool\":true")
        val tool = api.streamText(provider, listOf(UIMessage.user("lookup")), params("tool-turn", faultSession)).toList()
        assertFalse(tool.any { it is StreamChunk.GatewayCandidate })
        queue("json answer")
        val json = api.generateText(provider, listOf(UIMessage.user("json")), params("json-turn", faultSession))
        assertTrue(json.gatewayCandidateId?.isNotBlank() == true)
        queue("json failure", ",\"fail_receipt\":true")
        val rejected = runCatching {
            api.generateText(provider, listOf(UIMessage.user("json failure")), params("json-fault-turn", faultSession))
        }
        assertTrue(rejected.isFailure)
    }

    @Test fun wireHeadersAndCommitEventHaveRequiredOrder() {
        queue("wire")
        val requestBody = """{"model":"shared-model","stream":true,"messages":[{"role":"user","content":"wire"}]}"""
        val request = Request.Builder().url("$origin/v1/chat/completions")
            .header("Authorization", "Bearer gateway-token")
            .header("X-Ombre-Session-Id", "wire-${UUID.randomUUID()}")
            .header("X-Session-ID", UUID.randomUUID().toString())
            .header("X-Ombre-Turn-Id", "wire-turn")
            .header("X-Ombre-Candidate-Protocol", "1")
            .post(requestBody.toRequestBody("application/json".toMediaType())).build()
        http.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("provisional", response.header("X-Ombre-Candidate-State"))
            val id = requireNotNull(response.header("X-Ombre-Candidate-Id"))
            val body = response.body!!.string()
            assertTrue(body, body.contains("data: [DONE]"))
            assertTrue(body, body.contains("event: ombre.candidate-committed\ndata: $id"))
            assertTrue(body.indexOf("data: [DONE]") < body.indexOf("event: ombre.candidate-committed"))
        }
    }
}
