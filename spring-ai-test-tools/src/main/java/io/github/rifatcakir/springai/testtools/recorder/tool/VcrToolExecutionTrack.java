package io.github.rifatcakir.springai.testtools.recorder.tool;

/**
 * The on-disk fixture format for one recorded tool invocation — the tool-execution
 * counterpart of {@code io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack}
 * and {@code io.github.rifatcakir.springai.testtools.recorder.embedding.VcrEmbeddingTrack}.
 *
 * <p>A separate type rather than an extension of {@code VcrTrack}: a tool invocation is
 * not a model turn. It happens <em>between</em> model-turn advisor invocations,
 * orchestrated by Spring AI's {@code ToolCallingAdvisor} calling {@code
 * ToolCallingManager} directly — not something the model-call advisor has a slot for.
 * {@code VcrTrack.ToolCallSnapshot}/{@code ToolResponseSnapshot} capture what the
 * <em>model</em> said about a tool call in conversation history; this type captures what
 * the <em>tool itself</em> actually returned when invoked — a different fact that
 * happens to look similar. {@link #CURRENT_SCHEMA_VERSION} is this type's own,
 * independent version counter, unrelated to {@code VcrTrack}'s or {@code
 * VcrEmbeddingTrack}'s.
 *
 * <p>Keyed by tool name plus the exact argument string the model produced (see {@link
 * VcrToolExecutionCacheKeyGenerator}) — not by call order within a conversation. A tool
 * called twice with different arguments in one exchange (e.g. a weather lookup for two
 * different cities) naturally resolves to two different fixtures.
 *
 * @param schemaVersion format version for this fixture family, independent of {@code
 * VcrTrack.CURRENT_SCHEMA_VERSION} and {@code VcrEmbeddingTrack.CURRENT_SCHEMA_VERSION}
 * @param hash the SHA-256 key this fixture is filed under
 * @param recordedAt ISO-8601 instant the recording was made, for human triage only
 * @param canonicalRequest the exact normalized string the hash was computed over
 * @param toolName the tool's name, as the model addressed it
 * @param arguments the exact argument JSON string the model produced when this was
 * recorded, or {@code null} if it supplied none — recorded for reviewability; replay
 * keys purely off the filename hash, the same as every other fixture type in this project
 * @param result the tool's result exactly as it was returned, not parsed or validated by
 * this library — mirrors {@code VcrTrack.ToolResponseSnapshot#responseData()}
 * @param returnDirect whether the tool's declared {@code ToolMetadata#returnDirect()} was
 * {@code true} at record time — a static property of the tool itself, not of this
 * particular call, but recorded here rather than re-resolved at replay time so that
 * replaying never needs to look up the real {@code ToolCallback} at all (see {@code
 * docs/TOOL-ISOLATION-PRD.md} section 2.1)
 * @author Rifat Cakir
 */
public record VcrToolExecutionTrack(String schemaVersion, String hash, String recordedAt, String canonicalRequest,
		String toolName, String arguments, String result, boolean returnDirect) {

	/**
	 * {@code "1"}: the initial format. Independent of, and not comparable to, {@code
	 * VcrTrack.CURRENT_SCHEMA_VERSION} or {@code VcrEmbeddingTrack.CURRENT_SCHEMA_VERSION}
	 * — all three fixture families evolve separately.
	 */
	public static final String CURRENT_SCHEMA_VERSION = "1";

}
