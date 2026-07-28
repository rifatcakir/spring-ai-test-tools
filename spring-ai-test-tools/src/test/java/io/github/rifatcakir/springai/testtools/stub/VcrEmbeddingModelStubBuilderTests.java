package io.github.rifatcakir.springai.testtools.stub;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic, model-free, Spring-context-free tests for
 * {@link VcrEmbeddingModelStubBuilder} — every configured field verified field-by-field,
 * including the {@code embed(Document)} divergence from R4's own {@code VcrEmbeddingModel}
 * scope cut (see this builder's Javadoc for why).
 *
 * @author Rifat Cakir
 */
class VcrEmbeddingModelStubBuilderTests {

	@Test
	@DisplayName("an unconfigured stub answers with an empty vector, never null")
	void unconfiguredStubHasSensibleDefaults() {
		EmbeddingModel model = VcrStubs.embeddingModel().build();

		float[] vector = model.embed("anything");

		assertThat(vector).isEmpty();
	}

	@Test
	@DisplayName("respondingWith sets the exact vector returned by embed(String)")
	void respondingWithSetsVector() {
		EmbeddingModel model = VcrStubs.embeddingModel().respondingWith(new float[] { 0.1f, 0.2f, 0.3f }).build();

		assertThat(model.embed("anything")).containsExactly(0.1f, 0.2f, 0.3f);
	}

	@Test
	@DisplayName("respondingWith copies the vector rather than retaining the caller's array by reference")
	void respondingWithCopiesTheVector() {
		float[] original = { 1.0f, 2.0f };
		EmbeddingModel model = VcrStubs.embeddingModel().respondingWith(original).build();

		original[0] = 999f;

		assertThat(model.embed("anything")).as("mutating the array passed to respondingWith must not affect the stub")
			.containsExactly(1.0f, 2.0f);
	}

	@Test
	@DisplayName("a batch call(EmbeddingRequest) with N inputs answers with N results, all the same configured vector")
	void batchCallAnswersSameVectorForEveryInput() {
		EmbeddingModel model = VcrStubs.embeddingModel().respondingWith(new float[] { 0.5f, 0.6f }).build();

		EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("first", "second", "third"), null));

		assertThat(response.getResults()).hasSize(3);
		for (Embedding embedding : response.getResults()) {
			assertThat(embedding.getOutput()).containsExactly(0.5f, 0.6f);
		}
		assertThat(response.getResults().get(0).getIndex()).isEqualTo(0);
		assertThat(response.getResults().get(1).getIndex()).isEqualTo(1);
		assertThat(response.getResults().get(2).getIndex()).isEqualTo(2);
	}

	@Test
	@DisplayName("withModel overrides the reported model name")
	void withModelOverridesModelName() {
		EmbeddingModel model = VcrStubs.embeddingModel().withModel("text-embedding-3-small").build();

		EmbeddingResponse response = model.call(new EmbeddingRequest(List.of("x"), null));

		assertThat(response.getMetadata().getModel()).isEqualTo("text-embedding-3-small");
	}

	@Test
	@DisplayName("embed(Document) answers with the same configured vector as call(EmbeddingRequest) -- "
			+ "a deliberate divergence from R4's VcrEmbeddingModel, which has a real delegate to fall back on and "
			+ "this stub does not")
	void embedDocumentRoutesThroughTheSameCannedResponse() {
		EmbeddingModel model = VcrStubs.embeddingModel().respondingWith(new float[] { 0.7f, 0.8f }).build();

		float[] vector = model.embed(new Document("some RAG chunk content"));

		assertThat(vector).containsExactly(0.7f, 0.8f);
	}

	@Test
	@DisplayName("failingWith makes call(EmbeddingRequest) throw the exact exception instance")
	void failingWithThrowsFromCall() {
		RuntimeException timeout = new RuntimeException("timeout");
		EmbeddingModel model = VcrStubs.embeddingModel().failingWith(timeout).build();

		assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("x"), null))).isSameAs(timeout);
	}

	@Test
	@DisplayName("failingWith makes embed(Document) throw the exact exception instance too")
	void failingWithThrowsFromEmbedDocument() {
		RuntimeException timeout = new RuntimeException("timeout");
		EmbeddingModel model = VcrStubs.embeddingModel().failingWith(timeout).build();

		assertThatThrownBy(() -> model.embed(new Document("x"))).isSameAs(timeout);
	}

}
