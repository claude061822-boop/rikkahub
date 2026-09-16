package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressedHistoryAnnotationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `compressed history provenance survives persistence round trip`() {
        val message = UIMessage.compressedHistory("Earlier continuity facts")

        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<UIMessage>(encoded)

        assertEquals(MessageRole.USER, decoded.role)
        assertEquals("Earlier continuity facts", decoded.toText())
        assertTrue(decoded.isCompressedHistory())
        assertTrue(encoded.contains("compressed_history"))
    }

    @Test
    fun `legacy message without annotations remains compatible`() {
        val encoded = json.encodeToString(UIMessage.user("Legacy user message"))
        val legacyObject = JsonObject(
            json.parseToJsonElement(encoded).jsonObject - "annotations"
        )

        val decoded = json.decodeFromString<UIMessage>(legacyObject.toString())

        assertEquals(MessageRole.USER, decoded.role)
        assertEquals("Legacy user message", decoded.toText())
        assertTrue(decoded.annotations.isEmpty())
        assertFalse(decoded.isCompressedHistory())
    }

    @Test
    fun `ordinary user and assistant messages do not gain provenance`() {
        assertFalse(UIMessage.user("User request").isCompressedHistory())
        assertFalse(UIMessage.assistant("Assistant response").isCompressedHistory())
    }
}
