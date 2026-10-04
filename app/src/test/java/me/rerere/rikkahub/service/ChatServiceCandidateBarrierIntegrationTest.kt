package me.rerere.rikkahub.service

import android.app.Application
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.OpenAIProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.KeyRoulette
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.TranslationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.ChatToolFactory
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okio.Buffer
import kotlin.uuid.Uuid

class ChatServiceCandidateBarrierIntegrationTest {
    private val origin = System.getenv("ISOLATED_GATEWAY_URL") ?: "http://127.0.0.1:0"
    private val model = Model(modelId = "shared-model", displayName = "Test Model")
    private val provider = ProviderSetting.OpenAI(
        apiKey = "gateway-token", baseUrl = "$origin/v1", models = listOf(model),
    )
    private val bootstrapHttp = OkHttpClient()
    private val bootstrapApi = ChatCompletionsAPI(bootstrapHttp, KeyRoulette.default())

    @Before fun suppressForegroundService() {
        assumeTrue("opt-in isolated Gateway test", System.getenv("ISOLATED_GATEWAY_URL") != null)
        require(origin.toHttpUrl().host == "127.0.0.1" && origin.toHttpUrl().scheme == "http") {
            "Only a loopback Gateway is allowed"
        }
        val reset = Request.Builder().url("$origin/__test/reset-output")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        bootstrapHttp.newCall(reset).execute().use { assertEquals(200, it.code) }
        mockkObject(ChatGenerationForegroundService.Companion)
        every { ChatGenerationForegroundService.acquire(any(), any(), any()) } returns false
        mockkObject(PlaceholderTransformer)
        coEvery { PlaceholderTransformer.transform(any(), any()) } answers { secondArg() }
    }

    @After fun restoreForegroundService() {
        unmockkObject(ChatGenerationForegroundService.Companion)
        unmockkObject(PlaceholderTransformer)
    }

    private fun queue(text: String) {
        val request = Request.Builder().url("$origin/__test/enqueue")
            .post("{\"text\":\"$text\"}".toRequestBody("application/json".toMediaType()))
            .build()
        bootstrapHttp.newCall(request).execute().use { assertEquals(200, it.code) }
    }

    private fun inspect(seed: Seed): JsonObject {
        val request = Request.Builder()
            .url("$origin/__test/inspect?session=${seed.sessionId}&conversation=${seed.conversationId}")
            .build()
        bootstrapHttp.newCall(request).execute().use {
            return Json.parseToJsonElement(it.body.string()).jsonObject
        }
    }

    private data class Seed(val conversationId: Uuid, val sessionId: String, val node: MessageNode)

    private suspend fun seed(): Seed {
        val conversationId = Uuid.random()
        val sessionId = "app-test-${Uuid.random()}"
        val params = TextGenerationParams(
            model = model, sessionId = conversationId.toString(),
            turnId = "user-A-${Uuid.random()}",
            customHeaders = listOf(CustomHeader("X-Ombre-Session-Id", sessionId)),
        )
        val candidates = (1..3).map { number ->
            val text = "B$number"
            queue(text)
            val reply = bootstrapApi.generateText(provider, listOf(UIMessage.user("question A")), params)
            val id = requireNotNull(reply.gatewayCandidateId)
            bootstrapApi.selectGatewayCandidate(
                provider, conversationId.toString(), sessionId, id,
                requireNotNull(reply.gatewaySelectionUrl), (number - 1).toLong(),
            )
            UIMessage.assistant(text).copy(
                gatewayCandidateId = id,
                gatewaySelectionUrl = reply.gatewaySelectionUrl,
                gatewaySessionId = sessionId,
                gatewayProviderId = provider.id,
            )
        }
        return Seed(conversationId, sessionId, MessageNode(
            messages = candidates, selectIndex = 2,
            selectionRevision = 2, acknowledgedSelectionRevision = 2,
        ))
    }

    private class SelectionFault(
        private val kind: String,
    ) : Interceptor {
        val attempts = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completionEntered = CountDownLatch(1)
        val completionBody = AtomicReference<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            if (!chain.request().url.encodedPath.endsWith("/chat/candidate-selection")) {
                if (chain.request().url.encodedPath.endsWith("/chat/completions")) {
                    val buffer = Buffer()
                    chain.request().body?.writeTo(buffer)
                    completionBody.set(buffer.readUtf8())
                    completionEntered.countDown()
                }
                return chain.proceed(chain.request())
            }
            val attempt = attempts.incrementAndGet()
            if (attempt == 1) {
                entered.countDown()
                if (kind == "delay" || kind == "always-before-delay") {
                    check(release.await(8, TimeUnit.SECONDS))
                }
            }
            if ((kind == "before" && attempt == 1) || kind == "always-before-delay") {
                throw IOException("synthetic disconnect before Gateway submission")
            }
            val response = chain.proceed(chain.request())
            if (kind == "after" && attempt == 1) {
                assertEquals(200, response.code)
                response.close()
                throw IOException("synthetic lost acknowledgment after Gateway commit")
            }
            return response
        }
    }

    private data class AppHarness(
        val chat: ChatService,
        val generationStarted: CompletableDeferred<List<UIMessage>>,
        val scopeJob: Job,
    )

    private fun app(seed: Seed, fault: SelectionFault, realGeneration: Boolean = false): AppHarness {
        val sessionModel = model.copy(
            customHeaders = listOf(CustomHeader("X-Ombre-Session-Id", seed.sessionId)),
        )
        val assistant = Assistant(chatModelId = model.id, streamOutput = !realGeneration)
        val settings = Settings(
            chatModelId = model.id, assistantId = assistant.id,
            providers = listOf(provider.copy(models = listOf(sessionModel))),
            assistants = listOf(assistant), enableSuggestion = false,
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(settings)
        val repo = mockk<ConversationRepository>(relaxed = true)
        coEvery { repo.existsConversationById(any()) } returns true
        val providerManager = mockk<ProviderManager>()
        every { providerManager.getProviderByType(any<ProviderSetting.OpenAI>()) } returns
            OpenAIProvider(OkHttpClient.Builder().addInterceptor(fault).build())
        val context = mockk<Application>(relaxed = true)
        val generationMock = mockk<GenerationLoop>()
        val started = CompletableDeferred<List<UIMessage>>()
        every { generationMock.generateText(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            started.complete(thirdArg())
            emptyFlow<GenerationChunk>()
        }
        val generation = if (realGeneration) GenerationLoop(context, providerManager, Json) else generationMock
        val memory = mockk<MemoryRepository>()
        coEvery { memory.getMemoriesOfAssistant(any()) } returns emptyList()
        val tools = mockk<ChatToolFactory>()
        coEvery { tools.createTools(any(), any(), any(), any()) } returns emptyList()
        val mcp = mockk<McpManager>()
        every { mcp.getAllAvailableTools() } returns emptyList()
        val job = SupervisorJob()
        val scope = mockk<AppScope>()
        every { scope.coroutineContext } returns job + Dispatchers.Default
        val template = mockk<TemplateTransformer>()
        coEvery { template.transform(any(), any()) } answers { secondArg() }
        val chat = ChatService(
            context = context, appScope = scope,
            appEventBus = AppEventBus(), settingsStore = settingsStore,
            conversationRepo = repo, memoryRepository = memory,
            generationLoop = generation,
            translationHandler = mockk<TranslationHandler>(relaxed = true),
            templateTransformer = template,
            providerManager = providerManager, chatToolFactory = tools,
            mcpManager = mcp, filesManager = mockk<FilesManager>(relaxed = true),
            workspaceRepository = mockk<WorkspaceRepository>(relaxed = true),
            folderRepository = mockk<FolderRepository>(relaxed = true),
        )
        chat.updateConversationState(seed.conversationId) {
            Conversation(
                id = seed.conversationId, assistantId = assistant.id, title = "Existing",
                messageNodes = listOf(UIMessage.user("question A").toMessageNode(), seed.node),
            )
        }
        return AppHarness(chat, started, job)
    }

    @Test fun sendDuringUnacknowledgedSelectionWaitsForB1() = runBlocking {
        val seed = seed()
        val fault = SelectionFault("delay")
        val harness = app(seed, fault)
        try {
            val selection = async(Dispatchers.Default) {
                harness.chat.selectMessageNode(seed.conversationId, seed.node.id, 0)
            }
            assertTrue(fault.entered.await(5, TimeUnit.SECONDS))
            harness.chat.sendMessage(seed.conversationId, listOf(UIMessagePart.Text("question C")))
            delay(200)
            assertFalse("Provider started before selection acknowledgment", harness.generationStarted.isCompleted)
            fault.release.countDown()
            selection.await()
            val history = withTimeout(8_000) { harness.generationStarted.await() }
            assertEquals("B1", history.first { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }.toText())
            assertEquals(1, fault.attempts.get())
        } finally {
            fault.release.countDown()
            harness.chat.cleanup()
            harness.scopeJob.cancel()
        }
    }

    @Test fun realProviderHttpRequestStartsOnlyAfterSelectionAck() = runBlocking {
        val seed = seed()
        val nextQuestion = "question C ${Uuid.random()}"
        queue("answer C")
        val fault = SelectionFault("delay")
        val harness = app(seed, fault, realGeneration = true)
        try {
            val selection = async(Dispatchers.Default) {
                harness.chat.selectMessageNode(seed.conversationId, seed.node.id, 0)
            }
            assertTrue(fault.entered.await(5, TimeUnit.SECONDS))
            harness.chat.sendMessage(seed.conversationId, listOf(UIMessagePart.Text(nextQuestion)))
            delay(200)
            assertEquals("Provider HTTP request started before acknowledgment", 1L, fault.completionEntered.count)
            fault.release.countDown()
            selection.await()
            assertTrue("Provider HTTP request did not start", fault.completionEntered.await(8, TimeUnit.SECONDS))
            val body = requireNotNull(fault.completionBody.get())
            assertTrue(body, body.contains("B1"))
            assertFalse(body, body.contains("B3"))
            assertTrue(body, body.contains(nextQuestion))
            var gatewayState: JsonObject? = null
            withTimeout(8_000) {
                while (gatewayState == null) {
                    val state = inspect(seed)
                    val forwarded = state.getValue("forwarded").jsonArray
                    val matched = forwarded.any { batch ->
                        batch.jsonArray.any { message ->
                            message.jsonObject["content"]?.jsonPrimitive?.content == nextQuestion
                        }
                    }
                    if (matched) gatewayState = state else delay(50)
                }
            }
            val state = requireNotNull(gatewayState)
            assertEquals("B1", state.getValue("canonical").jsonPrimitive.content)
            assertEquals("B1", state.getValue("projection").jsonArray.single().jsonPrimitive.content)
        } finally {
            fault.release.countDown()
            harness.chat.cleanup()
            harness.scopeJob.cancel()
        }
    }

    @Test fun disconnectBeforeCommitRetriesAndThenAllowsB1() = runBlocking {
        val seed = seed()
        val fault = SelectionFault("before")
        val harness = app(seed, fault)
        try {
            harness.chat.selectMessageNode(seed.conversationId, seed.node.id, 0)
            assertEquals(2, fault.attempts.get())
            harness.chat.sendMessage(seed.conversationId, listOf(UIMessagePart.Text("question C")))
            val history = withTimeout(8_000) { harness.generationStarted.await() }
            assertEquals("B1", history.first { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }.toText())
        } finally {
            harness.chat.cleanup()
            harness.scopeJob.cancel()
        }
    }

    @Test fun committedButLostAckRetriesAsDuplicate() = runBlocking {
        val seed = seed()
        val fault = SelectionFault("after")
        val harness = app(seed, fault)
        try {
            harness.chat.selectMessageNode(seed.conversationId, seed.node.id, 0)
            assertEquals(2, fault.attempts.get())
            harness.chat.sendMessage(seed.conversationId, listOf(UIMessagePart.Text("question C")))
            val history = withTimeout(8_000) { harness.generationStarted.await() }
            assertEquals("B1", history.first { it.role == me.rerere.ai.core.MessageRole.ASSISTANT }.toText())
            val state = inspect(seed)
            assertEquals("B1", state.getValue("canonical").jsonPrimitive.content)
            assertEquals("B1", state.getValue("projection").jsonArray.single().jsonPrimitive.content)
        } finally {
            harness.chat.cleanup()
            harness.scopeJob.cancel()
        }
    }

    @Test fun persistentNetworkFailureBlocksNextGeneration() = runBlocking {
        val seed = seed()
        val fault = SelectionFault("always-before-delay")
        val harness = app(seed, fault)
        try {
            val selection = async(Dispatchers.Default) {
                runCatching { harness.chat.selectMessageNode(seed.conversationId, seed.node.id, 0) }
            }
            assertTrue(fault.entered.await(5, TimeUnit.SECONDS))
            harness.chat.sendMessage(seed.conversationId, listOf(UIMessagePart.Text("question C")))
            delay(200)
            assertFalse(harness.generationStarted.isCompleted)
            fault.release.countDown()
            assertTrue(selection.await().isFailure)
            withTimeout(12_000) {
                while (fault.attempts.get() < 6 || harness.chat.errors.value.isEmpty()) delay(50)
            }
            assertEquals("three selection retries and three send-barrier retries", 6, fault.attempts.get())
            assertFalse("Provider started despite failed selection", harness.generationStarted.isCompleted)
            val node = harness.chat.getConversationFlow(seed.conversationId).value.messageNodes[1]
            assertEquals(3, node.selectionRevision)
            assertEquals(2, node.acknowledgedSelectionRevision)
            assertEquals("B1", node.currentMessage.toText())
        } finally {
            fault.release.countDown()
            harness.chat.cleanup()
            harness.scopeJob.cancel()
        }
    }
}
