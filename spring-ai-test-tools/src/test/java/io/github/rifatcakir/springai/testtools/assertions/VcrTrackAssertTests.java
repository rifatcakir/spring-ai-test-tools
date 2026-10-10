package io.github.rifatcakir.springai.testtools.assertions;

import java.util.List;
import java.util.Map;

import io.github.rifatcakir.springai.testtools.recorder.track.VcrTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.github.rifatcakir.springai.testtools.assertions.VcrAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Proves {@link VcrTrackAssert}'s request-side checks against hand-built {@link VcrTrack}
 * objects — no model, no Docker, no fixture, the same style {@code ChatResponseAssertTests}
 * already uses for the response side. Every assertion gets both a passing case and a
 * failing case whose message is checked for content, not just presence.
 *
 * @author Rifat Cakir
 */
class VcrTrackAssertTests {

	private static VcrTrack.MessageSnapshot message(String type, String text) {
		return new VcrTrack.MessageSnapshot(type, text, List.of(), List.of(), List.of());
	}

	private static VcrTrack track(VcrTrack.MessageSnapshot... messages) {
		VcrTrack.RequestSnapshot request = new VcrTrack.RequestSnapshot("llama3.2", null, null, null, null,
				List.of(), List.of(messages), List.of(), null);
		VcrTrack.ResponseSnapshot response = new VcrTrack.ResponseSnapshot("id", "llama3.2",
				List.of(new VcrTrack.GenerationSnapshot("ok", "stop", List.of())), null, Map.of());
		return new VcrTrack(VcrTrack.CURRENT_SCHEMA_VERSION, "a".repeat(64), "2026-08-02T10:00:00Z", "irrelevant",
				request, response);
	}

	@Test
	@DisplayName("hasMessageContaining passes when any message, any role, contains the text")
	void hasMessageContainingPasses() {
		VcrTrack track = track(message("system", "You are terse."),
				message("user", "What is Nefeli Robotics' return policy?"));

		assertThat(track).hasMessageContaining("return policy");
	}

	@Test
	@DisplayName("hasMessageContaining fails with the actual message texts when nothing matches")
	void hasMessageContainingFails() {
		VcrTrack track = track(message("user", "hello"));

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThat(track).hasMessageContaining("goodbye"))
			.withMessageContaining("goodbye")
			.withMessageContaining("hello");
	}

	@Test
	@DisplayName("hasSystemMessageContaining only looks at system-role messages, not user messages")
	void hasSystemMessageContainingOnlyLooksAtSystemRole() {
		VcrTrack track = track(message("system", "Answer using only the documents below. 45-day return window."),
				message("user", "What is the return policy?"));

		assertThat(track).hasSystemMessageContaining("45-day return window");
	}

	@Test
	@DisplayName("hasSystemMessageContaining fails when the text is in a user message, not the system message")
	void hasSystemMessageContainingFailsWhenTextIsInTheWrongRole() {
		VcrTrack track = track(message("system", "You are terse."), message("user", "45-day return window?"));

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThat(track).hasSystemMessageContaining("45-day return window"))
			.withMessageContaining("45-day return window")
			.withMessageContaining("You are terse.");
	}

	@Test
	@DisplayName("hasNoMessageContaining passes when the text appears in no message")
	void hasNoMessageContainingPasses() {
		VcrTrack track = track(message("system", "Document: Nefeli Robotics Return Policy."),
				message("user", "What is the return policy?"));

		assertThat(track).hasNoMessageContaining("Lisbon");
	}

	@Test
	@DisplayName("hasNoMessageContaining fails and names which message leaked the text")
	void hasNoMessageContainingFailsAndNamesTheOffendingMessage() {
		VcrTrack track = track(message("system", "Document: Nefeli Robotics Office Locations. Lisbon and Nairobi."));

		assertThatExceptionOfType(AssertionError.class)
			.isThrownBy(() -> assertThat(track).hasNoMessageContaining("Lisbon"))
			.withMessageContaining("Lisbon")
			.withMessageContaining("Nairobi");
	}

	@Test
	@DisplayName("hasMessageCount passes on an exact match")
	void hasMessageCountPasses() {
		VcrTrack track = track(message("system", "x"), message("user", "y"));

		assertThat(track).hasMessageCount(2);
	}

	@Test
	@DisplayName("hasMessageCount fails with both the expected and actual count")
	void hasMessageCountFails() {
		VcrTrack track = track(message("user", "y"));

		assertThatExceptionOfType(AssertionError.class).isThrownBy(() -> assertThat(track).hasMessageCount(2))
			.withMessageContaining("2")
			.withMessageContaining("1");
	}

	@Test
	@DisplayName("assertions chain, AssertJ-style")
	void assertionsChain() {
		VcrTrack track = track(message("system", "Answer using only the documents below."),
				message("user", "What is the return policy?"));

		assertThat(track).hasMessageCount(2)
			.hasSystemMessageContaining("documents below")
			.hasMessageContaining("return policy")
			.hasNoMessageContaining("Lisbon");
	}

}
