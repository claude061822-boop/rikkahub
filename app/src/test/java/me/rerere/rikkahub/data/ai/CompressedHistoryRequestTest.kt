package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isCompressedHistory
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class CompressedHistoryRequestTest {
    @Test
    fun `compressed history becomes synthetic context exchange without changing recent messages`() {
        val summary = UIMessage.compressedHistory("The user chose option A.")
        val recentUser = UIMessage.user("What comes next?")
        val recentAssistant = UIMessage.assistant("We can continue with step two.")

        val result = listOf(summary, recentUser, recentAssistant)
            .withCompressedHistoryRequestSemantics()

        assertEquals(
            listOf(
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.USER,
                MessageRole.ASSISTANT,
            ),
            result.map { it.role },
        )
        assertEquals(COMPRESSED_HISTORY_CONTEXT_NOTICE, result[0].toText())
        assertEquals(summary.toText(), result[1].toText())
        assertTrue(result[0].isSynthetic)
        assertTrue(result[1].isSynthetic)
        assertTrue(result[1].isCompressedHistory())
        assertSame(recentUser, result[2])
        assertSame(recentAssistant, result[3])
        assertEquals(MessageRole.USER, summary.role)
    }

    @Test
    fun `multiple compressed summaries retain order`() {
        val first = UIMessage.compressedHistory("First historical chunk")
        val second = UIMessage.compressedHistory("Second historical chunk")
        val current = UIMessage.user("Current request")

        val result = listOf(first, second, current)
            .withCompressedHistoryRequestSemantics()

        assertEquals(5, result.size)
        assertEquals(COMPRESSED_HISTORY_CONTEXT_NOTICE, result[0].toText())
        assertEquals("First historical chunk", result[1].toText())
        assertEquals(COMPRESSED_HISTORY_CONTEXT_NOTICE, result[2].toText())
        assertEquals("Second historical chunk", result[3].toText())
        assertSame(current, result[4])
    }

    @Test
    fun `tool history remains adjacent and unchanged`() {
        val summary = UIMessage.compressedHistory("Earlier tool-independent facts")
        val toolRequest = UIMessage.user("Look this up")
        val toolResponse = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Using lookup"),
                UIMessagePart.Tool(
                    toolCallId = "call_1",
                    toolName = "lookup",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result")),
                ),
            ),
        )

        val result = listOf(summary, toolRequest, toolResponse)
            .withCompressedHistoryRequestSemantics()

        assertSame(toolRequest, result[2])
        assertSame(toolResponse, result[3])
        assertEquals(1, result[3].getTools().size)
    }

    @Test
    fun `conversation persistence reload keeps compressed provenance`() {
        val summary = UIMessage.compressedHistory("Persisted continuity facts")
        val storedMessages = JsonInstant.encodeToString(listOf(summary))
        val reloadedMessages = JsonInstant.decodeFromString<List<UIMessage>>(storedMessages)
        val reloadedConversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(reloadedMessages.single().toMessageNode()),
        )

        assertTrue(reloadedConversation.currentMessages.single().isCompressedHistory())
        assertEquals("Persisted continuity facts", reloadedConversation.currentMessages.single().toText())
    }

    @Test
    fun `regenerate keeps compressed provenance and branch behavior`() {
        val summary = UIMessage.compressedHistory("Branch continuity facts")
        val user = UIMessage.user("Question")
        val originalAnswer = UIMessage.assistant("Original answer")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(summary, user, originalAnswer).map { it.toMessageNode() },
        )
        val regeneratedAnswer = UIMessage.assistant("Regenerated answer")

        val regenerated = conversation.updateCurrentMessages(
            listOf(summary, user, regeneratedAnswer)
        )

        assertTrue(regenerated.currentMessages[0].isCompressedHistory())
        assertSame(user, regenerated.currentMessages[1])
        assertEquals("Regenerated answer", regenerated.currentMessages[2].toText())
        assertEquals(2, regenerated.messageNodes[2].messages.size)
        assertFalse(originalAnswer.isCompressedHistory())
    }
}
