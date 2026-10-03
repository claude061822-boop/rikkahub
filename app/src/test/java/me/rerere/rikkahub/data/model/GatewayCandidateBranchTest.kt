package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.Json
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GatewayCandidateBranchTest {
    @Test
    fun `regenerate and branch selection retain monotonic revision after serialization`() {
        val first = UIMessage.assistant("B1").copy(gatewayCandidateId = "candidate-1")
        val base = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(
                UIMessage.user("A").copy(gatewayTurnId = "turn-A").toMessageNode(),
                first.toMessageNode(),
            ),
        )
        val second = UIMessage.assistant("B2").copy(gatewayCandidateId = "candidate-2")
        val third = UIMessage.assistant("B3").copy(gatewayCandidateId = "candidate-3")
        val regenerated = base.updateCurrentMessages(listOf(base.currentMessages.first(), second))
            .updateCurrentMessages(listOf(base.currentMessages.first(), third))
        val node = regenerated.messageNodes.last()
        assertEquals(2L, node.selectionRevision)
        assertEquals(2, node.selectIndex)
        val restored = Json.decodeFromString<Conversation>(Json.encodeToString(regenerated))
        assertEquals("turn-A", restored.currentMessages.first().gatewayTurnId)
        val selected = restored.messageNodes.last().copy(selectIndex = 0, selectionRevision = 3)
        assertEquals("candidate-1", selected.currentMessage.gatewayCandidateId)
        assertEquals(3L, selected.selectionRevision)
    }
}
