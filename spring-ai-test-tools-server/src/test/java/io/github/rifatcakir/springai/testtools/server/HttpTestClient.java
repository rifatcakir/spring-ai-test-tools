package io.github.rifatcakir.springai.testtools.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Real HTTP against the running server, through the same JDK client the library's remote
 * store uses, so status codes and bodies are asserted exactly as a client sees them.
 */
final class HttpTestClient {

	private final HttpClient client = HttpClient.newHttpClient();

	private final String baseUrl;

	private final String token;

	HttpTestClient(int port) {
		this(port, null);
	}

	private HttpTestClient(int port, String token) {
		this.baseUrl = "http://localhost:" + port;
		this.token = token;
	}

	HttpTestClient withToken(String token) {
		return new HttpTestClient(Integer.parseInt(this.baseUrl.substring(this.baseUrl.lastIndexOf(':') + 1)), token);
	}

	HttpResponse<byte[]> get(String path) {
		return send(request(path).GET());
	}

	HttpResponse<byte[]> put(String path, byte[] body) {
		return put(path, body, "application/json");
	}

	HttpResponse<byte[]> put(String path, byte[] body, String contentType) {
		return send(request(path).header("Content-Type", contentType).PUT(HttpRequest.BodyPublishers.ofByteArray(body)));
	}

	HttpResponse<byte[]> delete(String path) {
		return send(request(path).DELETE());
	}

	private HttpRequest.Builder request(String path) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(this.baseUrl + path));
		if (this.token != null) {
			builder.header("Authorization", "Bearer " + this.token);
		}
		return builder;
	}

	private HttpResponse<byte[]> send(HttpRequest.Builder builder) {
		try {
			return this.client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
