package io.github.rifatcakir.springai.testtools.assertions;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import org.assertj.core.api.AbstractObjectAssert;

import org.springframework.util.Assert;

/**
 * Fluent, deterministic assertions against a {@link VcrTrack}'s <strong>request</strong>
 * side — what every other class in this package cannot check.
 *
 * <p>{@link ChatResponseAssert}/{@link ChatClientResponseAssert} assert on a {@code
 * ChatResponse}/{@code ChatClientResponse}, which only ever carries what came back from
 * the model — even on a replay, {@code VcrTrackMapper#toChatResponse(VcrTrack)} builds
 * its result entirely from {@code VcrTrack#response()} and never touches {@code
 * VcrTrack#request()}. There is deliberately no fluent way today to check what was
 * actually <em>sent</em> — a test wanting to verify that a RAG pipeline's retrieved
 * context genuinely made it into the prompt (as opposed to whether the model's answer
 * merely mentions it, which the response-side assertions above already cover) has to
 * reach for a {@link io.github.rifatcakir.springai.testtools.recorder.track.VcrTrackStore}
 * and walk {@code VcrTrack.RequestSnapshot} by hand. This class is that fluent surface.
 *
 * <p><strong>Not a RAG-specific assertion type</strong>, despite RAG-grounding checks
 * being the motivating case: Spring AI's own message model has no notion of "a retrieved
 * document" distinct from an ordinary message, so there is nothing RAG-specific to key an
 * assertion off. These are plain message-content checks, useful for verifying a
 * retrieval-augmented prompt was assembled correctly, a redactor didn't over-redact, or
 * any other "what did we actually send" question.
 *
 * <p>Every method here reads {@code VcrTrack.request().messages()} — a fixture's
 * committed, human-reviewable snapshot — never the live {@code Prompt} that produced it.
 * That is by design, the same way every other assertion in this package works identically
 * against a live response and a replay: a {@code VcrTrack} read from disk via {@code
 * VcrTrackStore#read} is exactly what a committed fixture says, whether the test run that
 * produced this assertion call was itself a live recording or a replay.
 *
 * @author Rifat Cakir
 */
public final class VcrTrackAssert extends AbstractObjectAssert<VcrTrackAssert, VcrTrack> {

	VcrTrackAssert(VcrTrack actual) {
		super(actual, VcrTrackAssert.class);
	}

	/**
	 * Asserts that at least one message, of any role, contains {@code expectedSubstring}
	 * in its text.
	 * @param expectedSubstring the text expected to appear in some message
	 * @return {@code this}, for chaining
	 */
	public VcrTrackAssert hasMessageContaining(String expectedSubstring) {
		isNotNull();
		Assert.hasText(expectedSubstring, "expectedSubstring must not be blank");
		if (messages().stream().map(VcrTrack.MessageSnapshot::text).noneMatch(text -> contains(text, expectedSubstring))) {
			failWithMessage("%nExpected some message to contain:%n  <%s>%nbut none of these did:%n  <%s>",
					expectedSubstring, messageTexts());
		}
		return this;
	}

	/**
	 * Like {@link #hasMessageContaining(String)}, narrowed to the {@code system}-role
	 * message — the common place a RAG pipeline's retrieved context, or any other
	 * request-scoped instructions, gets injected.
	 * @param expectedSubstring the text expected to appear in a system message
	 * @return {@code this}, for chaining
	 */
	public VcrTrackAssert hasSystemMessageContaining(String expectedSubstring) {
		isNotNull();
		Assert.hasText(expectedSubstring, "expectedSubstring must not be blank");
		List<String> systemTexts = messagesOfType("system").map(VcrTrack.MessageSnapshot::text).toList();
		if (systemTexts.stream().noneMatch(text -> contains(text, expectedSubstring))) {
			failWithMessage("%nExpected some system message to contain:%n  <%s>%nbut the system message(s) were:%n  <%s>",
					expectedSubstring, systemTexts);
		}
		return this;
	}

	/**
	 * Asserts that no message, of any role, contains {@code substring} — the mirror image
	 * of {@link #hasMessageContaining(String)}, for proving something did <em>not</em> leak
	 * into the prompt (an irrelevant retrieved document, a value a redactor was supposed to
	 * strip before this fixture was ever recorded).
	 * @param substring the text expected to be absent from every message
	 * @return {@code this}, for chaining
	 */
	public VcrTrackAssert hasNoMessageContaining(String substring) {
		isNotNull();
		Assert.hasText(substring, "substring must not be blank");
		List<String> offending = messages().stream()
			.map(VcrTrack.MessageSnapshot::text)
			.filter(text -> contains(text, substring))
			.toList();
		if (!offending.isEmpty()) {
			failWithMessage("%nExpected no message to contain:%n  <%s>%nbut found it in:%n  <%s>", substring, offending);
		}
		return this;
	}

	/**
	 * Asserts the exact number of messages in the request.
	 * @param expected the expected message count
	 * @return {@code this}, for chaining
	 */
	public VcrTrackAssert hasMessageCount(int expected) {
		isNotNull();
		int actual = messages().size();
		if (actual != expected) {
			failWithMessage("%nExpected <%s> message(s) but found <%s>:%n  <%s>", expected, actual, messageTexts());
		}
		return this;
	}

	private List<VcrTrack.MessageSnapshot> messages() {
		VcrTrack.RequestSnapshot request = this.actual.request();
		return (request == null || request.messages() == null) ? List.of() : request.messages();
	}

	private Stream<VcrTrack.MessageSnapshot> messagesOfType(String type) {
		return messages().stream().filter(message -> type.equals(message.type()));
	}

	private List<String> messageTexts() {
		return messages().stream().map(VcrTrack.MessageSnapshot::text).collect(Collectors.toList());
	}

	private static boolean contains(String text, String substring) {
		return text != null && text.contains(substring);
	}

}
