package me.rerere.rikkahub.data.ai

import me.rerere.ai.diagnostics.IncomingProvenanceTrace
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation

internal fun recordSelectedHistory(
    trace: IncomingProvenanceTrace,
    conversation: Conversation,
    assistant: Assistant,
    selectedHistory: List<UIMessage>,
) {
    trace.selectedHistory(selectedHistory, conversation.messageNodes.associate { node ->
        val selected = node.currentMessage
        selected.id to IncomingProvenanceTrace.Origin(
            source = if (assistant.presetMessages.any { it.id == selected.id }) "preset" else "conversation_history",
            nodeId = node.id.toString(), selectIndex = node.selectIndex,
            alternativesCount = node.messages.size,
            alternativeAnnotations = node.messages.map(IncomingProvenanceTrace::annotationNames),
        )
    })
}
