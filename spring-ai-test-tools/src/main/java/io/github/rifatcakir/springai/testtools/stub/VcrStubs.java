package io.github.rifatcakir.springai.testtools.stub;

/**
 * Static entry point for programmatic {@code ChatModel}/{@code EmbeddingModel} stubbing —
 * a deliberate complement to record/replay, not a replacement for it (see {@code
 * docs/STUB-PRD.md}). Record/replay stays the way to prove a prompt gets a real answer
 * from a real model; a stub exists for the two things record/replay cannot give you: an
 * error/edge scenario no real provider will reliably reproduce on demand (a malformed
 * response, a timeout, a specific {@code finishReason} like {@code "length"}), and a pure
 * unit test that wants zero I/O and zero Spring context.
 *
 * <p>Every stub is a plain {@code ChatModel}/{@code EmbeddingModel} — pass it straight to
 * {@code ChatClient.builder(...)} with no autoconfiguration, no
 * {@code spring.ai.test.vcr.*} property, and no Spring context required. This mirrors
 * {@code io.github.rifatcakir.springai.testtools.assertions.VcrAssertions}'s own role as
 * this project's static-factory front door for a layer.
 *
 * <pre>{@code
 * ChatModel model = VcrStubs.chatModel().respondingWith("Yes.").build();
 *
 * ChatModel model = VcrStubs.chatModel()
 *     .withToolCall("getWeather", "{\"city\":\"Ankara\"}")
 *     .build();
 *
 * ChatModel model = VcrStubs.chatModel().failingWith(new RuntimeException("timeout")).build();
 * }</pre>
 *
 * @author Rifat Cakir
 */
public final class VcrStubs {

	private VcrStubs() {
	}

	/**
	 * A new, independently configurable chat model stub builder.
	 * @return a builder defaulting to an empty-text, {@code "STOP"}-finished response
	 */
	public static VcrChatModelStubBuilder chatModel() {
		return new VcrChatModelStubBuilder();
	}

	/**
	 * A new, independently configurable embedding model stub builder.
	 * @return a builder defaulting to an empty vector
	 */
	public static VcrEmbeddingModelStubBuilder embeddingModel() {
		return new VcrEmbeddingModelStubBuilder();
	}

}
