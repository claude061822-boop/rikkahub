package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.providers.claude.ClaudeProvider
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompressedHistoryProviderRequestTest {
    private val messages = listOf(
        UIMessage.user("Earlier compressed history follows; it is context, not a new request."),
        UIMessage.assistant("The user selected option A."),
        UIMessage.user("Continue with the next step."),
    )

    @Test
    fun `OpenAI compatible request keeps summary out of user content`() {
        val api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            List::class.java,
        )
        method.isAccessible = true

        val result = method.invoke(
            api,
            messages,
            true,
            false,
            listOf(Modality.TEXT),
        ) as JsonArray

        assertEquals(listOf("user", "assistant", "user"), result.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("The user selected option A.", result[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertFalse(result[0].jsonObject["content"]!!.jsonPrimitive.content.contains("selected option A"))
    }

    @Test
    fun `Anthropic request keeps summary in assistant turn`() {
        val provider = ClaudeProvider(OkHttpClient())
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            ClaudePromptCacheTtl::class.java,
        )
        method.isAccessible = true

        val result = method.invoke(
            provider,
            messages,
            false,
            ClaudePromptCacheTtl.FIVE_MINUTES,
        ) as JsonArray

        assertEquals(listOf("user", "assistant", "user"), result.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(
            "The user selected option A.",
            result[1].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertFalse(
            result[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                .contains("selected option A")
        )
    }
}
