package me.rerere.ai.diagnostics

import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isCompressedHistory
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** Request-local sidecar. Never stored in a message, database, header, or provider JSON. */
class IncomingProvenanceTrace(
    private val conversationId: String,
    private val turnId: String?,
    private val provider: String,
    private val model: String,
    private val providerApi: String,
    private val sink: (String) -> Unit = { Log.i(TAG, it) },
) {
    // A diagnostic request identifier, not another session identity.
    private val traceId = turnId ?: Uuid.random().toString()
    private val origins = mutableMapOf<Uuid, Origin>()
    private val serializedOrigins = mutableListOf<Pair<Uuid, Origin>>()
    private var attempt = 0

    data class Origin(
        val source: String,
        val nodeId: String? = null,
        val selectIndex: Int? = null,
        val alternativesCount: Int? = null,
        val alternativeAnnotations: List<List<String>> = emptyList(),
        val transformer: String? = null,
        val parentMessageId: String? = null,
    )

    companion object {
        const val TAG = "IncomingProvenance"
        fun annotationNames(message: UIMessage): List<String> = message.annotations.map {
            when (it) {
                UIMessageAnnotation.CompressedHistory -> "compressed_history"
                is UIMessageAnnotation.UrlCitation -> "url_citation"
            }
        }
        private fun digest(content: String) = MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // Diagnostic failures must never fail or modify generation. Do not log exception text.
    private inline fun observe(block: () -> Unit) { try { block() } catch (_: Exception) { } }

    private fun emit(stage: String, fields: JsonObjectBuilder.() -> Unit) {
        sink(buildJsonObject {
            put("trace_id", traceId)
            put("conversation_id", conversationId)
            put("turn_id", turnId?.let(::JsonPrimitive) ?: JsonNull)
            put("provider", provider)
            put("model", model)
            put("provider_api", providerApi)
            put("attempt", attempt)
            put("stage", stage)
            fields()
        }.toString())
    }

    private fun JsonObjectBuilder.originFields(messageId: Uuid?, origin: Origin) {
        put("message_id", messageId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("source", origin.source)
        put("node_id", origin.nodeId?.let(::JsonPrimitive) ?: JsonNull)
        put("select_index", origin.selectIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("selected_alternative", origin.selectIndex?.let(::JsonPrimitive) ?: JsonNull)
        put("alternatives_count", origin.alternativesCount?.let(::JsonPrimitive) ?: JsonNull)
        put("alternative_annotations", JsonArray(origin.alternativeAnnotations.map { names ->
            JsonArray(names.map(::JsonPrimitive))
        }))
        put("transformer", origin.transformer?.let(::JsonPrimitive) ?: JsonNull)
        put("parent_message_id", origin.parentMessageId?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObjectBuilder.contentFields(content: String) {
        put("content_sha256", digest(content))
        put("content_chars", content.length)
    }

    private fun row(stage: String, idx: Int, message: UIMessage, changes: List<String> = emptyList(), transformer: String? = null) {
        emit(stage) {
            put("idx", idx)
            originFields(message.id, origins[message.id] ?: Origin("unknown"))
            transformer?.let { put("transformer", it) }
            put("role", message.role.name.lowercase())
            put("annotations", JsonArray(annotationNames(message).map(::JsonPrimitive)))
            put("is_synthetic", message.isSynthetic)
            put("parts_count", message.parts.size)
            put("text_part_count", message.parts.count { it is UIMessagePart.Text })
            contentFields(message.toText())
            put("changes", JsonArray(changes.map(::JsonPrimitive)))
        }
    }

    fun selectedHistory(messages: List<UIMessage>, selectedOrigins: Map<Uuid, Origin>) = observe {
        origins.putAll(selectedOrigins)
        stageUnsafe("selected_history", messages)
    }

    private fun stageUnsafe(stage: String, messages: List<UIMessage>) {
        emit(stage) { put("event", "snapshot"); put("message_count", messages.size) }
        messages.forEachIndexed { idx, message -> row(stage, idx, message) }
    }

    fun stage(stage: String, messages: List<UIMessage>) = observe {
        messages.forEach { message -> origins.putIfAbsent(message.id,
            Origin(if (message.isSynthetic && message.role.name == "SYSTEM") "system_context" else "unknown")) }
        stageUnsafe(stage, messages)
    }

    fun transformed(name: String, before: List<UIMessage>, after: List<UIMessage>) = observe {
        val old = before.withIndex().associateBy { it.value.id }
        val ids = after.map { it.id }.toSet()
        var added = 0
        var removed = 0
        var changed = 0
        after.forEachIndexed { idx, message ->
            val previous = old[message.id]
            val changes = mutableListOf<String>()
            if (previous == null) {
                added++
                val source = when (name.substringAfterLast('.')) {
                    "TimeReminderTransformer" -> "time_reminder"
                    "PromptInjectionTransformer" -> "prompt_injection"
                    else -> "transformer"
                }
                origins[message.id] = Origin(source, transformer = name)
                changes.add("added")
            } else {
                if (previous.index != idx) changes.add("idx_changed")
                if (previous.value.role != message.role) changes.add("role_changed")
                if (previous.value.annotations != message.annotations) changes.add("annotation_changed")
                if (previous.value.isSynthetic != message.isSynthetic) changes.add("synthetic_changed")
                // Include attachments and tool-part changes without logging their contents.
                if (previous.value.parts != message.parts) changes.add("content_changed")
                if (changes.isNotEmpty()) changed++
            }
            if (changes.isNotEmpty()) row("post_transform", idx, message, changes, name)
        }
        before.forEachIndexed { idx, message -> if (message.id !in ids) {
            removed++
            row("post_transform", idx, message, listOf("removed"), name)
        } }
        emit("post_transform") {
            put("event", "diff"); put("transformer", name)
            put("added", added); put("removed", removed); put("changed", changed)
            put("message_count", after.size)
        }
    }

    fun compressedSemantics(before: List<UIMessage>, after: List<UIMessage>) = observe {
        var idx = 0
        before.forEach { message ->
            if (message.isCompressedHistory()) {
                val origin = (origins[message.id] ?: Origin("unknown")).copy(
                    source = "compressed_history_semantics",
                    transformer = "withCompressedHistoryRequestSemantics",
                    parentMessageId = message.id.toString(),
                )
                origins[after[idx].id] = origin
                origins[after[idx + 1].id] = origin
                idx += 2
            } else idx++
        }
        stageUnsafe("post_compressed_semantics", after)
    }

    fun beginSerialization() = observe {
        attempt++
        serializedOrigins.clear()
    }

    /** Called for each item actually emitted by the serializer, including split tool items. */
    fun serialized(message: UIMessage, item: JsonElement) = observe {
        val origin = origins[message.id] ?: Origin("unknown")
        val idx = serializedOrigins.size
        serializedOrigins.add(message.id to origin)
        wireRow("serialized_provider_messages", idx, item, message.id, origin)
    }

    private fun wireRow(stage: String, idx: Int, item: JsonElement, messageId: Uuid?, origin: Origin) {
        val obj = item as? JsonObject
        val content = obj?.get("content") ?: obj?.get("output") ?: obj?.get("summary") ?: JsonNull
        emit(stage) {
            put("idx", idx)
            originFields(messageId, origin)
            put("role", obj?.get("role") ?: JsonNull)
            put("item_type", obj?.get("type") ?: JsonNull)
            contentFields(if (content is JsonPrimitive && content.isString) content.content else content.toString())
            put("tool_call_presence", obj?.containsKey("tool_calls") == true || obj?.get("type") == JsonPrimitive("function_call"))
            put("tool_result_presence", obj?.get("role") == JsonPrimitive("tool") || obj?.get("type") == JsonPrimitive("function_call_output"))
        }
    }

    fun finalPayload(body: JsonObject, customBody: List<CustomBody>, messagesKey: String) = observe {
        val overridden = customBody.any { it.key == messagesKey }
        val stage = if (messagesKey == "messages") "final_openai_messages" else "final_responses_input"
        val items = body[messagesKey] as? JsonArray
        emit(stage) {
            put("event", "request")
            put("custom_body_enabled", customBody.any { it.key.isNotBlank() })
            put("custom_body_has_messages", customBody.any { it.key == "messages" })
            put("custom_body_has_input", customBody.any { it.key == "input" })
            put("custom_body_has_instructions", customBody.any { it.key == "instructions" })
            put("messages_overridden", overridden)
            put("message_count", items?.size?.let(::JsonPrimitive) ?: JsonNull)
            put("messages_is_array", items != null)
        }
        items?.forEachIndexed { idx, item ->
            val linked = if (overridden) null else serializedOrigins.getOrNull(idx)
            wireRow(stage, idx, item, linked?.first,
                if (overridden) Origin("custom_body") else linked?.second ?: Origin("unknown"))
        }
        // Responses moves system text out of input. It has no final message index.
        if (messagesKey == "input" && body.containsKey("instructions")) {
            emit("final_responses_instructions") {
                put("source", if (customBody.any { it.key == "instructions" }) "custom_body" else "system_context")
                val content = body.getValue("instructions")
                contentFields(if (content is JsonPrimitive && content.isString) content.content else content.toString())
            }
        }
    }
}

/** One pending opt-in, in memory only. Permission-protected receiver is the application arming entry point. */
object IncomingProvenanceSwitch {
    private var armedConversation: String? = null
    private var expiresAtNanos = 0L

    @Synchronized fun arm(conversationId: String) {
        Uuid.parse(conversationId)
        armedConversation = conversationId
        expiresAtNanos = System.nanoTime() + 60_000_000_000L
    }

    @Synchronized fun consume(conversationId: String): Boolean {
        if (System.nanoTime() >= expiresAtNanos) armedConversation = null
        if (armedConversation != conversationId) return false
        armedConversation = null
        return true
    }
}
