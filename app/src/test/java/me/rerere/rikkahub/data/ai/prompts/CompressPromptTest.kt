package me.rerere.rikkahub.data.ai.prompts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressPromptTest {
    @Test
    fun `default prompt requests factual speaker attributed continuity`() {
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("factual continuity record"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("clear speaker attribution"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("confirmed facts"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("unresolved tasks"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("relationship facts"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("{target_tokens}"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("{additional_context}"))
    }

    @Test
    fun `default prompt rejects persona and reply instructions`() {
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("Do not:"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("\"You are\" or \"You should\""))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("reply strategy"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("roleplay"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("first-person perspective"))
        assertTrue(DEFAULT_COMPRESS_PROMPT.contains("persona-style sections"))
        assertFalse(DEFAULT_COMPRESS_PROMPT.contains("Start the output with a clear indicator"))
        assertFalse(DEFAULT_COMPRESS_PROMPT.contains("[Summary of previous conversation]"))
    }
}
