# Incoming Message Provenance Trace v1

Observability only. Default off. No roles, annotations, prompts, transformer order,
conversation persistence, provider payloads, request headers, or Gateway behavior change.
The known `editMessage()` annotation loss intentionally remains unchanged.

## Arm one request

Use an APK built from this patch in the package that already holds the target conversation.
No installation, history transfer, or device interaction is performed by this patch.
The permission-protected receiver is available in debug and release variants.
Use the conversation UUID shown by the client or an already-authorized diagnostic export.

Start this filtered logcat view **before** arming:

```sh
adb logcat -v threadtime -s IncomingProvenance:I '*:S'
```

In another terminal, replace PACKAGE and CONVERSATION_UUID:

```sh
adb shell am broadcast -n PACKAGE/me.rerere.rikkahub.diagnostics.IncomingProvenanceReceiver -a me.rerere.rikkahub.TRACE_NEXT_REQUEST --es conversation_id CONVERSATION_UUID
```

PACKAGE is `me.rerere.rikkahub` for release or `me.rerere.rikkahub.debug` for debug.
The app must already be running. An ordinary third-party app cannot send this broadcast:
the receiver requires `android.permission.DUMP`. There is no implicit intent filter.

Within 60 seconds, trigger the intended normal send or regenerate in that conversation.
One pending arm exists in process memory. Arming another conversation replaces it.
Requests in other conversations do not consume it. It expires after 60 seconds and
process restart clears it. No persistent/global trace mode exists.

The arm is consumed when `handleMessageComplete()` selects history, even if later
preparation fails. It traces only the first subsequent provider invocation in that
generation. Automatic transport retries share the sidecar and increment `attempt`;
later tool-loop model calls and background title/suggestion/compression requests are not traced.
Rearm explicitly for another observation. Logs are diagnostic output in normal logcat;
turning off collection does not erase existing logs.

## Stages and attribution

| Stage | Meaning |
| --- | --- |
| selected_history | Selected UI messages with node UUID, selected alternative and annotation metadata of all alternatives |
| pre_transform | After system construction and limitContext, before input transformers |
| post_transform | Per-transformer diff rows plus added/removed/changed counts, including unchanged transformer summaries |
| pre_compressed_semantics | Input to the existing compressed-history converter |
| post_compressed_semantics | Existing converter's output, with notice and summary linked to the original history node/message |
| provider_input | Exact UIMessage list passed to the provider |
| serialized_provider_messages | Each actual emitted serializer item, including split assistant/tool items, before custom-body merge |
| final_openai_messages | Final Chat Completions messages after custom-body merge, before JSON encoding |
| final_responses_input | Final Responses input after custom-body merge |
| final_responses_instructions | Hash/length of Responses instructions outside the input array |

Every row carries `trace_id`, `conversation_id`, `turn_id`, `provider` (provider UUID),
`model` (model ID), `provider_api`, and `attempt`. `trace_id` reuses the existing
Gateway turn ID when available; a local diagnostic UUID is used when it is absent.
No new network/session identity is sent. Separate rearms of the same existing turn
reuse its trace ID; separate those observations by logcat time and the arm event.

UI rows include `idx`, `message_id`, `node_id`, `select_index`, `selected_alternative`,
`alternatives_count`, `alternative_annotations`, `role`, annotation **type names only**,
`is_synthetic`, `source`, `transformer`, `parent_message_id`, `parts_count`,
`text_part_count`, `content_sha256`, and `content_chars`.
Built-in input transformer names are stable under release obfuscation.
Diff changes include `added`, `removed`, `idx_changed`, `role_changed`,
`annotation_changed`, `synthetic_changed`, and `content_changed`.

Final rows include actual array index, role/item type, source IDs, hash/length,
`tool_call_presence`, and `tool_result_presence`. Serializer output is observed directly;
no text matching is used to assign final indexes to nodes. Synthetic messages that never
belonged to a node have `node_id=null`, not a guessed adjacent node.

Sources are `conversation_history`, `preset`, `system_context`, `time_reminder`,
`prompt_injection`, `transformer`, `compressed_history_semantics`, `custom_body`, or `unknown`.
An edited compressed node can show `annotations=[]` on the selected alternative and
`compressed_history` on an earlier alternative. This exposes the existing loss without fixing it.

Find the row with `stage=final_openai_messages` and `idx=1`. Its `message_id` and
`node_id` map to selected_history. For compressed conversion, `parent_message_id`
identifies the original summary; the new USER notice has its own transient message ID.
For transformer additions, `source` and `transformer` identify the creator instead.

## Custom body and privacy

Request summary rows include `custom_body_enabled` (any nonblank effective key),
`custom_body_has_messages`, `custom_body_has_input`, `custom_body_has_instructions`,
`messages_overridden`, `messages_is_array`, and `message_count`.
If custom body replaces the array, final rows explicitly have source=custom_body and
no asserted history node/message ID; pre-override serializer rows are still available.
Even an identical array replacement is attributed to custom_body. No custom values are logged.

UI hashes use `UIMessage.toText()` (text parts joined with a newline), SHA-256 of UTF-8.
Wire hashes use the decoded string content, or compact JSON for structured content;
tool output/Responses summary is hashed when content is absent. `content_chars` is
Kotlin String.length (UTF-16 code units), not bytes or tokens. Structured content hashes
also cover URLs/media references, but no references or contents are output.
UI and final hashes can differ legitimately for structured/multipart content.

The trace logs no message text, citation text/URLs, custom-body values, API keys, or tool
arguments. Existing full request/response/event/error-body adapter dumps were replaced
with fixed status logs. Diagnostics are excluded from serialized TextGenerationParams;
sidecars do not persist to messages or conversations. Sink exceptions do not fail requests.

## Limits and validation

Preset attribution is an ID match against current assistant preset configuration.
Historically removed/replaced or edited presets cannot be inferred from text and remain
conversation_history. Legacy unannotated messages remain unannotated; semantic origin
that was never persisted cannot be reconstructed by this trace.
Final array tracing covers OpenAI Chat Completions and Responses. Other providers may
show application stages with provider_api=unsupported, without final wire attribution.
This records the client-built payload, not a downstream proxy rewrite. A live device
observation still has to establish the incident's actual source.

Tests intercept HTTP locally and mock repositories; they do not connect to a Gateway or
write actual conversation/memory databases. They compare serialized payload bytes with
trace on/off, verify exact idx=1 lineage and split tools, compressed semantics, legacy
annotations, existing edit loss, transformer attribution, privacy, diagnostic-sink
failure isolation, transient params, and one-shot scoping/concurrency/expiry.

Offline validation command in this checkout (Google Services config is absent):

```sh
./gradlew --offline :ai:testDebugUnitTest :app:testDebugUnitTest --tests '*IncomingProvenanceTraceTest' --tests '*IncomingHistoryTraceTest' --tests '*CompressedHistory*' --tests '*ChatServiceTest' -x :app:processDebugGoogleServices
```

No live broadcast, APK installation, production observation, database cleanup, commit,
or push is performed as part of this implementation.

Validation result: 59 targeted tests passed, including 14 new diagnostic tests; zero
failures, errors, or skips. Debug Kotlin compilation and the merged debug manifest
succeeded, with the receiver requiring android.permission.DUMP. git diff --check passed.
The repository has no google-services.json; unit validation skipped
processDebugGoogleServices. A separate release manifest check could not complete
because the absent Google Services generated resources are required by release
navigation resource processing. No deployable release APK or live device activation
was verified. The entry point is in the main source set; observation requires a
successfully built and installed patched APK in the package holding the conversation.
