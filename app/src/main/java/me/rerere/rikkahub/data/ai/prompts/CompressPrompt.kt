package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_COMPRESS_PROMPT = """
    Compress the following conversation into a concise, factual continuity record for later turns.

    Requirements:
    1. Preserve important statements with clear speaker attribution, confirmed facts, decisions, unresolved tasks, current state, relationship facts, and context needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output the summary directly without any explanations or meta-commentary
    5. State information as historical facts, using concise speaker-attributed sentences or bullets
    6. Use {locale} language

    Do not:
    - Address the assistant with instructions such as "You are" or "You should"
    - Prescribe behavior, reply strategy, tone, relationship style, or roleplay
    - Turn user requests or preferences into rules for future replies
    - Write from a character's first-person perspective or add assistant operating instructions
    - Require or invent persona-style sections or headings

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()
