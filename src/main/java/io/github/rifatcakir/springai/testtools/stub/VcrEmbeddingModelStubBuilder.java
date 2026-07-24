package io.github.rifatcakir.springai.testtools.stub;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.util.Assert;

/**
 * Builds a canned {@code EmbeddingModel} — see {@link VcrStubs} for why this exists and
 * how it differs from record/replay.
 *
 * <p>A batch call with N inputs answers with N {@code Embedding} results, all carrying
 * the same configured vector — the simplest default that still makes
 * {@code embed(List<String>)} (which routes through {@code call(EmbeddingRequest)})
 * behave sensibly, without forcing a test to configure one vector per input for a
 * scenario that doesn't care about that distinction.
 *
 * <p>{@code embed(Document)} answers with the same configured vector too — a deliberate
 * divergence from {@code VcrEmbeddingModel} (R4), which leaves {@code embed(Document)} as
 * an uncached pass-through to a real delegate. A stub has no delegate to fall back to;
 * leaving this unimplemented would silently break any RAG-shaped test that hands this
 * stub to code expecting a full, working {@code EmbeddingModel} substitute, defeating the
 * entire point of stubbing one.
 *
 * @author Rifat Cakir
 */
public final class VcrEmbeddingModelStubBuilder {

	private float[] vector = new float[0];

	private String modelName = "stub";

	private RuntimeException failure;

	VcrEmbeddingModelStubBuilder() {
	}

	/**
	 * The vector every call to the built model answers with. Default: an empty vector.
	 * @param vector the vector to return — copied, not retained by reference
	 * @return this builder
	 */
	public VcrEmbeddingModelStubBuilder respondingWith(float[] vector) {
		Assert.notNull(vector, "vector must not be null");
		this.vector = vector.clone();
		return this;
	}

	/**
	 * Overrides the response metadata's model name. Default: {@code "stub"}. Cosmetic
	 * only.
	 * @param modelName the model name the response's metadata should report
	 * @return this builder
	 */
	public VcrEmbeddingModelStubBuilder withModel(String modelName) {
		Assert.hasText(modelName, "modelName must not be empty");
		this.modelName = modelName;
		return this;
	}

	/**
	 * Makes the built model throw this exact exception instead of returning a response,
	 * from both {@code call(EmbeddingRequest)} and {@code embed(Document)}.
	 * @param exception the exact exception instance to throw on every call
	 * @return this builder
	 */
	public VcrEmbeddingModelStubBuilder failingWith(RuntimeException exception) {
		Assert.notNull(exception, "exception must not be null");
		this.failure = exception;
		return this;
	}

	/**
	 * @return an {@code EmbeddingModel} that always answers as configured
	 */
	public EmbeddingModel build() {
		return new StubEmbeddingModel(this.vector.clone(), this.modelName, this.failure);
	}

	private static final class StubEmbeddingModel implements EmbeddingModel {

		private final float[] vector;

		private final String modelName;

		private final RuntimeException failure;

		StubEmbeddingModel(float[] vector, String modelName, RuntimeException failure) {
			this.vector = vector;
			this.modelName = modelName;
			this.failure = failure;
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			Assert.notNull(request, "request must not be null");
			if (this.failure != null) {
				throw this.failure;
			}

			List<Embedding> embeddings = new ArrayList<>();
			int index = 0;
			for (String ignored : request.getInstructions()) {
				embeddings.add(new Embedding(this.vector.clone(), index++));
			}
			EmbeddingResponseMetadata metadata = new EmbeddingResponseMetadata(this.modelName,
					new DefaultUsage(0, 0, 0));
			return new EmbeddingResponse(embeddings, metadata);
		}

		@Override
		public float[] embed(Document document) {
			Assert.notNull(document, "document must not be null");
			if (this.failure != null) {
				throw this.failure;
			}
			return this.vector.clone();
		}

	}

}
