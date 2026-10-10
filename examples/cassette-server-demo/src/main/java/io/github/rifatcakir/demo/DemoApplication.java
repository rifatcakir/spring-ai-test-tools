package io.github.rifatcakir.demo;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;

/**
 * Asks one question through {@link ChatClient} and prints the answer. Nothing in here knows
 * about the Cassette Server: recording and replay are switched on in {@code application.yml}.
 */
@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		// spring-ai-test-tools 0.1.x predates the remote store (0.2.0) and would silently
		// ignore "store: remote", recording to local files instead. Stop instead.
		if (!ClassUtils.isPresent("io.github.rifatcakir.springai.testtools.recorder.remote.CassetteServerClient",
				null)) {
			System.err.println("This spring-ai-test-tools jar has no remote store. Build the demo together with the "
					+ "library: mvn -pl examples/cassette-server-demo -am package -DskipTests");
			System.exit(1);
		}
		SpringApplication.run(DemoApplication.class, args);
	}

	@Bean
	CommandLineRunner ask(ChatClient.Builder chatClient) {
		return args -> {
			String question = (args.length > 0) ? String.join(" ", args) : "What is the capital of France?";
			String answer = chatClient.build().prompt().user(question).call().content();
			System.out.println();
			System.out.println("  Question: " + question);
			System.out.println("  Answer:   " + answer);
			System.out.println();
		};
	}

	/**
	 * A stand-in for a real model, so the demo needs no API key and no Ollama. It stamps the
	 * current time into every answer: if a later run prints the same time, the answer came
	 * from the Cassette Server, not from the model. Swap in any real model starter and
	 * nothing else changes.
	 */
	@Bean
	ChatModel fakeModel() {
		return new ChatModel() {

			@Override
			public ChatResponse call(Prompt prompt) {
				String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
				System.out.println("  >>> the model was called");
				String text = "Paris. (answered by the model at " + time + ")";
				return ChatResponse.builder().generations(List.of(new Generation(new AssistantMessage(text)))).build();
			}

		};
	}

}
