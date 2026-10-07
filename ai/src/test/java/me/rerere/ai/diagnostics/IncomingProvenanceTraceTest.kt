package me.rerere.ai.diagnostics

import kotlinx.serialization.json.*
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class IncomingProvenanceTraceTest {
    private val conversationId = Uuid.random().toString()
    private val model = Model(modelId = "trace-test-model")
    private val provider = ProviderSetting.OpenAI(baseUrl = "https://gateway.invalid/v1", apiKey = "never-used")
    private val secret = "PRIVATE_USER_CANARY_4fd830"
    private val toolMessage = UIMessage.assistant(secret).copy(parts = listOf(
        UIMessagePart.Text(secret),
        UIMessagePart.Tool(toolCallId = "call-1", toolName = "lookup", input = "{}",
            output = listOf(UIMessagePart.Text(secret))),
        UIMessagePart.Text("after tool"),
    ))
    private val messages = listOf(UIMessage.system("system-canary"), UIMessage.user(secret), toolMessage)
    private fun trace(logs: MutableList<String>) = IncomingProvenanceTrace(
        conversationId, "existing-turn", provider.id.toString(), model.modelId, "chat_completions", logs::add,
    ).also { trace -> trace.selectedHistory(messages, messages.associate { message ->
        message.id to IncomingProvenanceTrace.Origin("conversation_history", "node-${message.id}", 0, 1)
    }) }

    private fun chatBody(params: TextGenerationParams, stream: Boolean): JsonObject {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod("buildChatCompletionRequest",
            List::class.java, TextGenerationParams::class.java, ProviderSetting.OpenAI::class.java,
            Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(api, messages, params, provider, stream) as JsonObject
    }

    @Test fun `chat payload is byte identical with trace including custom override and tool splitting`() {
        for (stream in listOf(false, true)) for (custom in listOf(emptyList(), listOf(
            CustomBody("messages", buildJsonArray { add(buildJsonObject {
                put("role", "user"); put("content", "override-canary")
            }) })
        ))) {
            val logs = mutableListOf<String>()
            val params = TextGenerationParams(model, customBody = custom, sessionId = conversationId, turnId = "existing-turn")
            val disabled = chatBody(params, stream)
            val enabled = chatBody(params.copy(provenanceTrace = trace(logs)), stream)
            assertEquals(json.encodeToString(disabled), json.encodeToString(enabled))
            val rows = logs.map { Json.parseToJsonElement(it).jsonObject }
                .filter { it["stage"] == JsonPrimitive("final_openai_messages") && it.containsKey("idx") }
            if (custom.isEmpty()) {
                assertEquals(disabled["messages"]!!.jsonArray.size, rows.size)
                assertEquals(messages[1].id.toString(), rows[1]["message_id"]!!.jsonPrimitive.content)
                assertEquals("node-${messages[1].id}", rows[1]["node_id"]!!.jsonPrimitive.content)
                assertEquals(toolMessage.id.toString(), rows[3]["message_id"]!!.jsonPrimitive.content)
                assertEquals(JsonPrimitive(true), rows[3]["tool_result_presence"])
            } else {
                assertEquals(JsonPrimitive("custom_body"), rows.single()["source"])
                assertEquals(JsonNull, rows.single()["message_id"])
            }
            assertFalse(logs.joinToString().contains(secret))
            assertFalse(logs.joinToString().contains("override-canary"))
        }
    }

    @Test fun `responses payload is byte identical and input indexes retain sources`() {
        val api = ResponseAPI(OkHttpClient(), KeyRoulette.default())
        for (custom in listOf(emptyList(), listOf(CustomBody("input", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "override-canary") })
        }), CustomBody("instructions", JsonPrimitive("private-instructions"))))) {
            val logs = mutableListOf<String>()
            val params = TextGenerationParams(model, customBody = custom, sessionId = conversationId)
            val enabledTrace = IncomingProvenanceTrace(conversationId, null, provider.id.toString(), model.modelId, "responses", logs::add)
            enabledTrace.selectedHistory(messages, messages.associate { it.id to IncomingProvenanceTrace.Origin("conversation_history", "node-${it.id}") })
            val disabled = api.buildRequestBody(provider, messages, params, false)
            val enabled = api.buildRequestBody(provider, messages, params.copy(provenanceTrace = enabledTrace), false)
            assertEquals(json.encodeToString(disabled), json.encodeToString(enabled))
            val rows = logs.map { Json.parseToJsonElement(it).jsonObject }
                .filter { it["stage"] == JsonPrimitive("final_responses_input") && it.containsKey("idx") }
            assertEquals(disabled["input"]!!.jsonArray.size, rows.size)
            if (custom.isEmpty()) assertEquals(messages[1].id.toString(), rows[0]["message_id"]!!.jsonPrimitive.content)
            else assertEquals(JsonPrimitive("custom_body"), rows[0]["source"])
            assertTrue(logs.any { it.contains("final_responses_instructions") })
            assertFalse(logs.joinToString().contains(secret))
            assertFalse(logs.joinToString().contains("private-instructions"))
        }
    }

    @Test fun `trace sidecar is excluded from serialized params`() {
        val params = TextGenerationParams(model, sessionId = conversationId)
        assertEquals(json.encodeToString(params), json.encodeToString(params.copy(provenanceTrace = trace(mutableListOf()))))
    }

    @Test fun `failed diagnostic sink cannot affect provider payload`() {
        val params = TextGenerationParams(model, sessionId = conversationId)
        val broken = IncomingProvenanceTrace(conversationId, null, "provider", model.modelId, "chat_completions") {
            error("diagnostic sink unavailable")
        }
        broken.selectedHistory(messages, emptyMap())
        assertEquals(chatBody(params, false), chatBody(params.copy(provenanceTrace = broken), false))
    }

    @Test fun `legacy user has empty annotations and citation contents are never logged`() {
        val logs = mutableListOf<String>()
        val trace = trace(logs)
        trace.stage("provider_input", listOf(UIMessage.user(secret), UIMessage.assistant(secret).copy(
            annotations = listOf(UIMessageAnnotation.UrlCitation(secret, "https://private.invalid/canary")),
        )))
        val rows = logs.map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["stage"] == JsonPrimitive("provider_input") && it.containsKey("idx") }
        assertEquals(JsonArray(emptyList()), rows[0]["annotations"])
        assertEquals(64, rows[0]["content_sha256"]!!.jsonPrimitive.content.length)
        assertEquals(JsonPrimitive(secret.length), rows[0]["content_chars"])
        assertFalse(logs.joinToString().contains(secret))
        assertFalse(logs.joinToString().contains("private.invalid"))
    }

    @Test fun `one shot is disabled by default conversation scoped and consumed once`() {
        val first = Uuid.random().toString()
        val other = Uuid.random().toString()
        assertFalse(IncomingProvenanceSwitch.consume(first))
        IncomingProvenanceSwitch.arm(first)
        assertFalse(IncomingProvenanceSwitch.consume(other))
        assertTrue(IncomingProvenanceSwitch.consume(first))
        assertFalse(IncomingProvenanceSwitch.consume(first))
    }

    @Test fun `expired arm cannot enable a later request`() {
        val id = Uuid.random().toString()
        IncomingProvenanceSwitch.arm(id)
        val expiry = IncomingProvenanceSwitch::class.java.getDeclaredField("expiresAtNanos")
        expiry.isAccessible = true
        expiry.setLong(IncomingProvenanceSwitch, System.nanoTime() - 1)
        assertFalse(IncomingProvenanceSwitch.consume(id))
    }

    @Test fun `concurrent consumers claim the armed conversation only once`() {
        val id = Uuid.random().toString()
        IncomingProvenanceSwitch.arm(id)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val results = (1..16).map { executor.submit<Boolean> { IncomingProvenanceSwitch.consume(id) } }
            assertEquals(1, results.count { it.get() })
        } finally { executor.shutdownNow() }
    }

    @Test fun `new arm replaces prior pending arm instead of enabling a global mode`() {
        val first = Uuid.random().toString()
        val second = Uuid.random().toString()
        IncomingProvenanceSwitch.arm(first)
        IncomingProvenanceSwitch.arm(second)
        assertFalse(IncomingProvenanceSwitch.consume(first))
        assertTrue(IncomingProvenanceSwitch.consume(second))
    }
}
