package me.rerere.rikkahub.data.ai

import android.app.Application
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.diagnostics.IncomingProvenanceTrace
import me.rerere.ai.provider.*
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.transformers.*
import me.rerere.rikkahub.data.datastore.*
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.*
import me.rerere.rikkahub.service.ChatService
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class IncomingHistoryTraceTest {
    private fun trace(logs: MutableList<String>) = IncomingProvenanceTrace(
        "conversation", "existing-turn", "provider", "model", "chat_completions", logs::add,
    )
    private fun rows(logs: List<String>, stage: String) = logs.map { Json.parseToJsonElement(it).jsonObject }
        .filter { it["stage"] == JsonPrimitive(stage) && it.containsKey("idx") }

    @Test fun `compressed exchange preserves history lineage and unannotated user stays legacy`() {
        val logs = mutableListOf<String>()
        val summary = UIMessage.compressedHistory("summary-private-canary")
        val legacy = UIMessage.user("legacy-private-canary")
        val assistant = Assistant()
        val conversation = Conversation(assistantId = assistant.id, messageNodes = listOf(summary, legacy).map { it.toMessageNode() })
        val trace = trace(logs)
        recordSelectedHistory(trace, conversation, assistant, conversation.currentMessages)
        trace.stage("pre_compressed_semantics", conversation.currentMessages)
        val after = conversation.currentMessages.withCompressedHistoryRequestSemantics()
        trace.compressedSemantics(conversation.currentMessages, after)
        val pre = rows(logs, "pre_compressed_semantics")
        assertEquals(JsonPrimitive("user"), pre[0]["role"])
        assertEquals(buildJsonArray { add("compressed_history") }, pre[0]["annotations"])
        assertEquals(JsonArray(emptyList()), pre[1]["annotations"])
        val post = rows(logs, "post_compressed_semantics")
        assertEquals(listOf("user", "assistant", "user"), post.map { it["role"]!!.jsonPrimitive.content })
        for (idx in 0..1) {
            assertEquals(JsonPrimitive(true), post[idx]["is_synthetic"])
            assertEquals(JsonPrimitive("compressed_history_semantics"), post[idx]["source"])
            assertEquals(JsonPrimitive(conversation.messageNodes[0].id.toString()), post[idx]["node_id"])
            assertEquals(JsonPrimitive(summary.id.toString()), post[idx]["parent_message_id"])
        }
        assertEquals(JsonPrimitive("conversation_history"), post[2]["source"])
        assertEquals(MessageRole.USER, summary.role)
        assertFalse(logs.joinToString().contains("private-canary"))
    }

    @Test fun `transform pipeline attributes time reminder generic additions changes and removals`() = runBlocking {
        val logs = mutableListOf<String>()
        val legacy = UIMessage.user("private-legacy")
        val added = UIMessage.assistant("private-addition")
        val modifier = object : InputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>) = messages.map {
                if (it.id == legacy.id) it.copy(role = MessageRole.ASSISTANT,
                    annotations = listOf(UIMessageAnnotation.CompressedHistory), isSynthetic = true) else it
            } + added
        }
        val remover = object : InputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>) = messages.filter { it.id != legacy.id }
        }
        val trace = trace(logs)
        val result = listOf(legacy).transforms(
            listOf(TimeReminderTransformer, modifier, remover), mockk(relaxed = true), Model(),
            Assistant(enableTimeReminder = true), Settings(), provenanceTrace = trace,
        )
        assertEquals(2, result.size)
        val post = rows(logs, "post_transform")
        assertTrue(post.any { it["source"] == JsonPrimitive("time_reminder") && it["changes"]!!.jsonArray.contains(JsonPrimitive("added")) })
        assertTrue(post.any { it["source"] == JsonPrimitive("transformer") && it["message_id"] == JsonPrimitive(added.id.toString()) })
        assertTrue(post.any { it["changes"]!!.jsonArray.contains(JsonPrimitive("role_changed")) &&
            it["changes"]!!.jsonArray.contains(JsonPrimitive("annotation_changed")) && it["changes"]!!.jsonArray.contains(JsonPrimitive("synthetic_changed")) })
        assertTrue(post.any { it["message_id"] == JsonPrimitive(legacy.id.toString()) && it["changes"]!!.jsonArray.contains(JsonPrimitive("removed")) })
    }

    @Test fun `prompt injection and preset are attributed separately from history`() {
        val logs = mutableListOf<String>()
        val preset = UIMessage.assistant("preset-canary")
        val user = UIMessage.user("user-canary")
        val assistant = Assistant(presetMessages = listOf(preset))
        val conversation = Conversation(assistantId = assistant.id, messageNodes = listOf(preset, user).map { it.toMessageNode() })
        val trace = trace(logs)
        recordSelectedHistory(trace, conversation, assistant, conversation.currentMessages)
        val injection = PromptInjection.ModeInjection(position = InjectionPosition.TOP_OF_CHAT, content = "injection-canary")
        val after = applyInjections(conversation.currentMessages, mapOf(InjectionPosition.TOP_OF_CHAT to listOf(injection)))
        trace.transformed(PromptInjectionTransformer.javaClass.name, conversation.currentMessages, after)
        assertEquals(JsonPrimitive("preset"), rows(logs, "selected_history")[0]["source"])
        assertTrue(rows(logs, "post_transform").any { it["source"] == JsonPrimitive("prompt_injection") })
        assertFalse(logs.joinToString().contains("canary"))
    }

    @Test fun `existing editMessage annotation loss is observed without repairing behavior`() = runBlocking {
        val assistant = Assistant()
        val settings = Settings(assistantId = assistant.id, assistants = listOf(assistant))
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns MutableStateFlow(settings)
        val repo = mockk<ConversationRepository>(relaxed = true)
        coEvery { repo.existsConversationById(any()) } returns true
        val job = SupervisorJob()
        val scope = mockk<AppScope>()
        every { scope.coroutineContext } returns job + Dispatchers.Unconfined
        val chat = ChatService(
            context = mockk(relaxed = true), appScope = scope, appEventBus = AppEventBus(), settingsStore = store,
            conversationRepo = repo, memoryRepository = mockk(relaxed = true), generationLoop = mockk(relaxed = true),
            translationHandler = mockk(relaxed = true), templateTransformer = mockk(relaxed = true),
            providerManager = mockk(relaxed = true), chatToolFactory = mockk(relaxed = true), mcpManager = mockk(relaxed = true),
            filesManager = mockk(relaxed = true), workspaceRepository = mockk(relaxed = true), folderRepository = mockk(relaxed = true),
        )
        val summary = UIMessage.compressedHistory("old-summary-canary")
        val conversation = Conversation(assistantId = assistant.id, messageNodes = listOf(summary.toMessageNode()))
        try {
            chat.updateConversationState(conversation.id) { conversation }
            chat.editMessage(conversation.id, summary.id, listOf(UIMessagePart.Text("edited-summary-canary")))
            val edited = chat.getConversationFlow(conversation.id).value
            val message = edited.currentMessages.single()
            assertEquals(MessageRole.USER, message.role)
            assertTrue(message.annotations.isEmpty()) // Freeze the existing defect, do not fix it here.
            assertTrue(edited.messageNodes.single().messages.first().isCompressedHistory())
            assertEquals(listOf(message), listOf(message).withCompressedHistoryRequestSemantics())
            val logs = mutableListOf<String>()
            recordSelectedHistory(trace(logs), edited, assistant, edited.currentMessages)
            val row = rows(logs, "selected_history").single()
            assertEquals(JsonPrimitive(1), row["select_index"])
            assertEquals(JsonArray(emptyList()), row["annotations"])
            assertEquals(buildJsonArray { add(buildJsonArray { add("compressed_history") }); add(JsonArray(emptyList())) }, row["alternative_annotations"])
            assertFalse(logs.joinToString().contains("canary"))
            coVerify(exactly = 1) { repo.updateConversation(any()) } // Mocked repository; no real DB.
        } finally { chat.cleanup(); job.cancel() }
    }

    @Test fun `GenerationLoop trace leaves actual HTTP payload unchanged and exposes every stage`() = runBlocking {
        val model = Model(modelId = "test-model")
        val provider = ProviderSetting.OpenAI(baseUrl = "https://gateway.invalid/v1", apiKey = "unused", models = listOf(model))
        val manager = mockk<ProviderManager>()
        val bodies = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            bodies.add(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"id":"test-response","model":"test-model","choices":[{"message":{"role":"assistant","content":"reply"},"finish_reason":"stop"}]}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        every { manager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns OpenAIProvider(http)
        val assistant = Assistant(chatModelId = model.id, streamOutput = false, systemPrompt = "system")
        val settings = Settings(providers = listOf(provider), assistants = listOf(assistant), chatModelId = model.id, assistantId = assistant.id)
        val summary = UIMessage.compressedHistory("private-summary")
        val user = UIMessage.user("private-user").copy(gatewayTurnId = "existing-turn")
        val conversation = Conversation(assistantId = assistant.id, messageNodes = listOf(summary, user).map { it.toMessageNode() })
        val logs = mutableListOf<String>()
        val trace = IncomingProvenanceTrace(conversation.id.toString(), "${conversation.id}:existing-turn", provider.id.toString(), model.modelId, "chat_completions", logs::add)
        recordSelectedHistory(trace, conversation, assistant, conversation.currentMessages)
        val loop = GenerationLoop(mockk<Application>(relaxed = true), manager, Json)
        val transformer = object : InputMessageTransformer {
            override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>) = messages
        }
        for (enabled in listOf(false, true)) loop.generateText(settings, model, conversation.currentMessages,
            inputTransformers = listOf(transformer), assistant = assistant, conversationId = conversation.id,
            provenanceTrace = if (enabled) trace else null).toList()
        assertEquals(bodies[0], bodies[1])
        val stages = logs.map { Json.parseToJsonElement(it).jsonObject["stage"]!!.jsonPrimitive.content }.toSet()
        assertTrue(stages.containsAll(listOf("selected_history", "pre_transform", "post_transform", "pre_compressed_semantics",
            "post_compressed_semantics", "provider_input", "serialized_provider_messages", "final_openai_messages")))
        val finalRows = rows(logs, "final_openai_messages")
        assertEquals(JsonPrimitive("user"), finalRows[1]["role"])
        assertEquals(JsonPrimitive("compressed_history_semantics"), finalRows[1]["source"])
        assertEquals(JsonPrimitive(conversation.messageNodes[0].id.toString()), finalRows[1]["node_id"])
        assertEquals(JsonPrimitive(summary.id.toString()), finalRows[1]["parent_message_id"])
        val summaryRow = finalRows.first { it["message_id"] == JsonPrimitive(summary.id.toString()) }
        assertEquals(JsonPrimitive("assistant"), summaryRow["role"])
        assertEquals(JsonPrimitive(conversation.messageNodes[0].id.toString()), summaryRow["node_id"])
        assertFalse(logs.joinToString().contains("private-summary"))
        assertFalse(logs.joinToString().contains("private-user"))
    }
}
