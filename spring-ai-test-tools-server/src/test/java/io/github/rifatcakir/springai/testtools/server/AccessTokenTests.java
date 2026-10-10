package io.github.rifatcakir.springai.testtools.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token enforcement on both APIs. The flags are passed as command-line arguments, exactly
 * as {@code java -jar … --admin-token=… --client-token=…} would, so the mapping in {@code
 * application.properties} is tested along with the checks.
 */
class AccessTokenTests {

	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
			args = { "--storage-dir=target/test-cassettes/access-tokens", "--admin-token=admin-secret", "--client-token=client-secret",
					"--cassette.namespace-tokens.billing=billing-secret" })
	class WithTokens {

		@LocalServerPort
		int port;

		private HttpTestClient http;

		@BeforeEach
		void client() {
			this.http = new HttpTestClient(this.port);
		}

		@Test
		void adminRequestsWithoutOrWithAWrongTokenAre401() {
			assertThat(this.http.get("/__admin/namespaces").statusCode()).isEqualTo(401);
			assertThat(this.http.withToken("client-secret").get("/__admin/namespaces").statusCode()).isEqualTo(401);
			assertThat(this.http.get("/__admin/namespaces").headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
		}

		@Test
		void theAdminTokenOpensTheAdminApi() {
			assertThat(this.http.withToken("admin-secret").get("/__admin/namespaces").statusCode()).isEqualTo(200);
		}

		@Test
		void theClientApiNeedsTheClientToken() {
			String path = "/api/v1/ns/svc/chat/" + TestDocuments.hash("token");
			assertThat(this.http.get(path).statusCode()).isEqualTo(401);
			assertThat(this.http.withToken("wrong").get(path).statusCode()).isEqualTo(401);
			assertThat(this.http.withToken("client-secret").get(path).statusCode()).isEqualTo(404);
		}

		@Test
		void aNamespaceTokenReplacesTheGlobalOneForThatNamespace() {
			String path = "/api/v1/ns/billing/chat/" + TestDocuments.hash("token");
			assertThat(this.http.withToken("client-secret").get(path).statusCode()).isEqualTo(401);
			assertThat(this.http.withToken("billing-secret").get(path).statusCode()).isEqualTo(404);
		}

		@Test
		void aRejectedPutStoresNothing() {
			String hash = TestDocuments.hash("rejected-put");
			assertThat(this.http.put("/api/v1/ns/svc/chat/" + hash, TestDocuments.chat(hash)).statusCode()).isEqualTo(401);
			assertThat(this.http.withToken("client-secret").get("/api/v1/ns/svc/chat/" + hash).statusCode())
				.isEqualTo(404);
		}

		@Test
		void healthNeedsNoToken() {
			assertThat(this.http.get("/actuator/health").statusCode()).isEqualTo(200);
		}

	}

	@Nested
	@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
			args = "--storage-dir=target/test-cassettes/no-admin-token")
	class WithoutAnAdminToken {

		@LocalServerPort
		int port;

		@Test
		void theAdminApiIsDisabledRatherThanOpen() {
			HttpTestClient http = new HttpTestClient(this.port);
			assertThat(http.get("/__admin/namespaces").statusCode()).isEqualTo(403);
			assertThat(http.withToken("anything").get("/__admin/namespaces").statusCode()).isEqualTo(403);
		}

	}

}
