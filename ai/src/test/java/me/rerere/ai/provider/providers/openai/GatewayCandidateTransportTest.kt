package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.Buffer

class GatewayCandidateTransportTest {
    @Test
    fun `selection uses same gateway origin and carries explicit identities`() = runBlocking {
        var captured: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            captured = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val provider = ProviderSetting.OpenAI(
            apiKey = "test-token", baseUrl = "https://gateway.example/v1",
        )
        ChatCompletionsAPI(client, KeyRoulette.default()).selectGatewayCandidate(
            provider, "conversation-1", "shared-session", "candidate-1",
            "https://gateway.example/v1/chat/candidate-selection", 3,
        )
        val request = requireNotNull(captured)
        assertEquals("/v1/chat/candidate-selection", request.url.encodedPath)
        assertEquals("conversation-1", request.header("X-Session-ID"))
        assertEquals("shared-session", request.header("X-Ombre-Session-Id"))
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("\"candidate_id\":\"candidate-1\""))
        assertTrue(body.contains("\"revision\":3"))
    }
}
