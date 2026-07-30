package io.github.rifatcakir.springai.testtools.recorder.track;

import java.util.List;
import java.util.Map;

/**
 * The on-disk fixture format — one "track" on the tape.
 *
 * <p>Spring AI's response domain is not safe to serialise directly. {@code ChatResponse},
 * {@code Generation} and {@code AssistantMessage} lack public no-arg constructors,
 * {@code AssistantMessage}'s full constructor is {@code protected}, and
 * {@code ChatResponseMetadata} carries a {@code nativeUsage} field holding the raw
 * provider SDK object, which drags an unbounded and provider-specific object graph into
 * the JSON. Round-tripping any of that through Jackson produces either a crash or a fixture
 * that only deserialises on the machine that wrote it.
 *
 * <p>So this record is a deliberate, narrow projection: everything a test can meaningfully
 * assert on, and nothing else. Fields are flat, all types are JSON primitives or records,
 * and every component is reconstructible through Spring AI's public builders.
 *
 * <p>It is also a security boundary. Because interception happens at the advisor layer
 * rather than the HTTP layer, no {@code Authorization} header, bearer token or API key
 * ever reaches this type — there is nothing to filter, unlike VCR.py where redacting
 * {@code sk-...} from committed cassettes is a required manual step.
 *
 * @param schemaVersion format version, so a future change can migrate rather than crash
 * @param hash the SHA-256 key this fixture is filed under
 * @param recordedAt ISO-8601 instant the recording was made, for human triage only
 * @param canonicalRequest the exact normalized string the hash was computed over
 * @param request a human-readable snapshot of what was sent
 * @param response the replayable payload
 * @author Rifat Cakir
 */
public record VcrTrack(String schemaVersion, String hash, String recordedAt, String canonicalRequest,
		RequestSnapshot request, ResponseSnapshot response) {

	/**
	 * {@code "2"}: {@link MessageSnapshot} gained {@code toolCalls} and
	 * {@code toolResponses}, capturing what {@code AssistantMessage.getToolCalls()} and
	 * {@code ToolResponseMessage.getResponses()} actually carry for a message in
	 * conversation history — previously silently dropped, because both
	 * {@code AssistantMessage.getText()} (when a turn is tool-calls-only) and
	 * {@code ToolResponseMessage.getText()} (always) return an empty string, and this
	 * fixture format and the hash it participates in were built entirely from
	 * {@code Message.getText()}. Two conversations differing only in which tool was
	 * called, or what a tool responded with, canonicalized identically and could replay
	 * the wrong turn under {@link io.github.rifatcakir.springai.testtools.recorder.VcrScope#INSIDE_TOOL_LOOP}
	 * — the one scope where the advisor ever sees a prompt containing these message
	 * types at all.
	 *
	 * <p>This bump is additive, not a hard compatibility gate: a {@code "1"} fixture
	 * still deserializes under schema {@code "2"} code — the two new
	 * {@code MessageSnapshot} fields simply come back {@code null} for a record that
	 * never had them, verified in
	 * {@code VcrTrackStoreRoundTripTests#schemaVersion1FixtureWithoutToolFieldsStillReplays}.
	 * That {@code null} is harmless rather than merely tolerated: nothing in this library
	 * ever reads {@code RequestSnapshot.messages()} back after deserialization — it exists
	 * for the hash (computed from the live {@code Prompt}, never from a stored fixture) and
	 * for human review, not for replay, which is driven entirely by {@code ResponseSnapshot}.
	 * There is deliberately no reader-side version check
	 * enforcing this; the version number here is provenance, telling a reviewer which
	 * behaviour produced a given fixture, not a migration gate this library runs.
	 *
	 * <p>{@code "3"}: {@link RequestSnapshot} gained {@code structuredOutput}, capturing
	 * what {@code ChatClient...entity(Class)} stashes on {@code ChatClientRequest.context()}
	 * (the format instructions and JSON schema {@code ChatModelCallAdvisor} — the terminal
	 * advisor — splices into the message text after every other advisor, including this
	 * one, has already run). Before this bump, {@code VcrCacheKeyGenerator} canonicalized
	 * only the un-augmented {@link org.springframework.ai.chat.prompt.Prompt}, so two
	 * structurally different {@code entity()} target types sharing identical prompt text
	 * hashed identically and collided on one fixture — confirmed end to end against a real
	 * model in {@code OllamaStructuredOutputEndToEndTests} before this fix, the same
	 * "diagnose against a real model before touching the hash" discipline schema {@code "2"}
	 * followed for tool calls. Additive, same as {@code "2"}: a {@code "1"} or {@code "2"}
	 * fixture still deserializes, with {@code structuredOutput} simply {@code null} — nothing
	 * in this library reads {@code RequestSnapshot} back after deserialization, so that
	 * {@code null} is harmless, not merely tolerated.
	 *
	 * <p>{@code "4"}: no new field — the canonicalization formula itself changed, which
	 * this version number exists just as much to signal. A tool's input schema and an
	 * {@code entity()} call's format instructions/JSON schema are rendered by Jackson's
	 * pretty printer, which embeds the recording JVM's own {@code System.lineSeparator()}
	 * ({@code \r\n} on Windows, {@code \n} on Linux/macOS). Before this bump, {@code
	 * VcrCacheKeyGenerator} hashed that text as-is, so a fixture recorded on Windows and
	 * replayed in CI on Linux hashed the same logical schema differently and missed for a
	 * reason with nothing to do with the actual request — confirmed for real, not
	 * hypothetical: a fixture recorded on Windows failed exactly this way on a Linux
	 * GitHub Actions runner. Both {@code VcrCacheKeyGenerator} (the hash) and this
	 * mapper's {@code toToolSnapshots}/{@code toStructuredOutputSnapshot} (the stored,
	 * human-readable fixture fields, kept consistent with what was actually hashed) now
	 * collapse {@code CRLF}/lone {@code CR} to {@code LF} in schema/format text before
	 * either uses it — deliberately not applied to message or response text, which is not
	 * generated by this library's own toolchain and could in principle carry a real,
	 * model-visible difference. Additive in the sense that mattered here too: no {@code
	 * RequestSnapshot}/{@code StructuredOutputSnapshot} field was added or removed, so a
	 * {@code "3"} fixture still deserializes without any change.
	 *
	 * <p>{@code "5"}: two independent additions, both closing a confirmed silent-collision
	 * gap found in a pre-deploy canonicalization audit (see {@code
	 * docs/V2-CANONICALIZATION-AUDIT.md}), the same "diagnose against real bytecode/a real
	 * model before touching the hash" discipline as every prior bump.
	 * <ol>
	 * <li><strong>Native vs. text-spliced structured output.</strong> {@code
	 * entity(Class)} and {@code entity(Class, spec -> spec.useProviderStructuredOutput())}
	 * populate identical {@code OUTPUT_FORMAT}/{@code STRUCTURED_OUTPUT_SCHEMA} context
	 * values for the same target type, but are genuinely different requests: the former
	 * splices format instructions into message text, the latter sets a native {@code
	 * StructuredOutputChatOptions.outputSchema} field and leaves the message untouched.
	 * Confirmed via decompiling {@code ChatModelCallAdvisor}/{@code DefaultChatClient} that
	 * a {@code STRUCTURED_OUTPUT_NATIVE} context key is present if and only if the native
	 * path is used, and confirmed empirically that the two collided on one hash before this
	 * bump. See {@code VcrCacheKeyGenerator#appendStructuredOutput}.</li>
	 * <li><strong>Multimodal content.</strong> {@code UserMessage}/{@code AssistantMessage}
	 * both carry a {@code List<Media>} (images, audio) that previously fed neither the hash
	 * nor this fixture — only {@code Message.getText()} did. Two prompts differing only in
	 * which image was attached, identical caption text, used to collide on one fixture;
	 * confirmed empirically before this fix. {@link MessageSnapshot} gains {@code media}
	 * (new {@link MediaSnapshot} record: {@code mimeType}, {@code id}, a digest/URI token
	 * for the data — see {@code VcrCacheKeyGenerator#appendMessageMedia}'s Javadoc for why
	 * {@code Media.getName()} is deliberately excluded, a real gotcha caught while
	 * implementing this, not assumed: it is a fresh random UUID on every construction, and
	 * hashing it would break replay for the identical image).</li>
	 * </ol>
	 * Both additive, same as every prior bump: no existing field removed, and neither
	 * changes the canonical form for a request that carries neither native structured
	 * output nor media — a {@code "4"} fixture, and every fixture recorded before this
	 * bump, still deserializes and still hashes exactly as before.
	 */
	public static final String CURRENT_SCHEMA_VERSION = "5";

	/**
	 * What went to the model. Recorded for reviewability — a fixture diff in a pull
	 * request should be readable — and never used during replay, which keys purely off
	 * the filename hash.
	 * @param model the model name requested, or {@code null} if the request carried no options
	 * @param temperature sampling temperature, or {@code null} if unset
	 * @param topP nucleus sampling parameter, or {@code null} if unset
	 * @param topK top-k sampling parameter, or {@code null} if unset
	 * @param maxTokens the requested completion length limit, or {@code null} if unset
	 * @param stopSequences stop sequences, in the sorted order used to compute the hash — not
	 * necessarily the order the caller supplied them in
	 * @param messages every message in the prompt, in original conversation order
	 * @param tools tool definitions available to the model for this request
	 * @param structuredOutput the {@code entity()} format instructions/JSON schema this
	 * request carried, or {@code null} if this was not a structured-output call
	 */
	public record RequestSnapshot(String model, Double temperature, Double topP, Integer topK, Integer maxTokens,
			List<String> stopSequences, List<MessageSnapshot> messages, List<ToolDefinitionSnapshot> tools,
			StructuredOutputSnapshot structuredOutput) {
	}

	/**
	 * What {@code ChatClient...entity(Class)} stashed on {@code ChatClientRequest.context()}
	 * for this request — both participate in the hash (see {@code VcrCacheKeyGenerator}),
	 * captured here too purely for fixture reviewability, the same reason
	 * {@link ToolDefinitionSnapshot} exists alongside the hash rather than only inside it.
	 * @param format the human-readable format instructions {@code StructuredOutputConverter
	 * #getFormat()} produced, or {@code null} if this request had none
	 * @param jsonSchema the JSON Schema {@code StructuredOutputConverter#getJsonSchema()}
	 * produced, or {@code null} if this request had none
	 */
	public record StructuredOutputSnapshot(String format, String jsonSchema) {
	}

	/**
	 * One message in a prompt or a tool-call round-trip.
	 *
	 * <p>{@code toolCalls} and {@code toolResponses} are mutually exclusive in practice —
	 * an {@code AssistantMessage} may carry the former, a {@code ToolResponseMessage} the
	 * latter, and every other message type carries neither — but both are always
	 * non-{@code null}, empty when not applicable, so a caller never needs a null check to
	 * iterate them. {@code media} follows the same convention, populated only for a
	 * {@code UserMessage}/{@code AssistantMessage} that actually attached something.
	 * @param type the message role, e.g. {@code "user"}, {@code "system"}, {@code "assistant"}
	 * @param text the message content, after any {@code VcrPromptNormalizer}s have run;
	 * empty for a tool-calls-only assistant turn or for any {@code ToolResponseMessage},
	 * since neither carries plain text — see {@code toolCalls}/{@code toolResponses}
	 * @param toolCalls tools this message asked to have called — populated only for an
	 * {@code AssistantMessage} that made tool calls, empty otherwise
	 * @param toolResponses tool results this message is reporting back to the model —
	 * populated only for a {@code ToolResponseMessage}, empty otherwise
	 * @param media images/audio this message attached (schema {@code "5"}) — empty for a
	 * message that carried none, which is every message recorded before this field existed
	 */
	public record MessageSnapshot(String type, String text, List<ToolCallSnapshot> toolCalls,
			List<ToolResponseSnapshot> toolResponses, List<MediaSnapshot> media) {
	}

	/**
	 * One {@code Media} attachment (schema {@code "5"}) — a human-reviewable fingerprint,
	 * not the raw attachment: a fixture is committed and reviewed in a pull request, and
	 * nobody reviews a multi-megabyte base64 image inline in a JSON diff. See {@code
	 * VcrCacheKeyGenerator#appendMessageMedia}'s Javadoc for the full reasoning, including
	 * why {@code Media.getName()} is deliberately not captured here either (a fresh random
	 * UUID on every construction, confirmed by decompiling {@code Media}'s own
	 * constructors — capturing it would make two recordings of the identical image show
	 * different fixture content for no reason a reviewer could explain).
	 * @param mimeType the attachment's MIME type, e.g. {@code "image/png"}
	 * @param id the attachment's explicit id, or {@code null} if none was set (the common
	 * case: {@code Media}'s own public constructors always leave this {@code null})
	 * @param dataToken {@code "sha256:" + hex digest} for binary data, or the literal URI
	 * string for a {@code URI}-backed attachment — mirrors exactly what
	 * {@code VcrCacheKeyGenerator} hashed, so a reviewer sees the same fingerprint that
	 * determined whether this fixture was a hit or a miss
	 */
	public record MediaSnapshot(String mimeType, String id, String dataToken) {
	}

	/**
	 * Tool definitions are part of the hash because changing a tool's name, description or
	 * schema changes what the model does, even when the prompt text is untouched.
	 * @param name the tool's name, as the model sees it
	 * @param description the tool's description, as the model sees it
	 * @param inputSchema the tool's JSON Schema for its input, as a JSON string
	 */
	public record ToolDefinitionSnapshot(String name, String description, String inputSchema) {
	}

	/**
	 * What came back, reduced to the parts that survive a round trip.
	 * @param id the provider's response identifier, or {@code null} if none was present
	 * @param model the model that actually answered, or {@code null} if the response carried
	 * no metadata
	 * @param generations every candidate answer the model returned, usually exactly one
	 * @param usage token accounting, or {@code null} if the response carried none
	 * @param metadata additional string-valued response metadata beyond {@code id}/{@code model}/{@code usage}
	 */
	public record ResponseSnapshot(String id, String model, List<GenerationSnapshot> generations, UsageSnapshot usage,
			Map<String, String> metadata) {
	}

	/**
	 * One candidate answer.
	 * @param text the assistant's message text; empty, never {@code null}, if the turn was
	 * tool-calls-only
	 * @param finishReason why the model stopped, e.g. {@code "stop"} or {@code "tool_calls"};
	 * {@code null} if the provider didn't report one
	 * @param toolCalls tool calls the model requested in this turn, empty if none
	 */
	public record GenerationSnapshot(String text, String finishReason, List<ToolCallSnapshot> toolCalls) {
	}

	/**
	 * Mirrors {@code AssistantMessage.ToolCall}.
	 * @param id the provider's identifier for this specific tool call, used to correlate it
	 * with the tool's result on the next turn
	 * @param type the tool call type; {@code "function"} for every provider currently
	 * supported by Spring AI
	 * @param name the name of the tool being called
	 * @param arguments the tool's input arguments, as a JSON-encoded string exactly as the
	 * model returned them — not parsed or validated by this library
	 */
	public record ToolCallSnapshot(String id, String type, String name, String arguments) {
	}

	/**
	 * Mirrors {@code ToolResponseMessage.ToolResponse}. A single {@code ToolResponseMessage}
	 * can carry more than one of these — one {@code @Tool} method's result per parallel
	 * tool call the assistant made in the preceding turn.
	 * @param id the tool call id this response answers, correlating it back to the specific
	 * entry in the preceding assistant message's {@code toolCalls}
	 * @param name the tool's name
	 * @param responseData the tool's result exactly as it was returned, not parsed or
	 * validated by this library
	 */
	public record ToolResponseSnapshot(String id, String name, String responseData) {
	}

	/**
	 * Token accounting for one response. Never contains a provider's raw, non-portable usage
	 * object — see the class-level Javadoc.
	 * @param promptTokens tokens consumed by the request, or {@code null} if the provider
	 * didn't report it
	 * @param completionTokens tokens generated in the response, or {@code null} if the
	 * provider didn't report it
	 * @param totalTokens the provider-reported total, or {@code null} if not reported;
	 * not guaranteed to equal {@code promptTokens + completionTokens} for every provider
	 */
	public record UsageSnapshot(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
	}

}
