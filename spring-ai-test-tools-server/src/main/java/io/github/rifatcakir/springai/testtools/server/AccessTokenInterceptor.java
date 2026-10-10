package io.github.rifatcakir.springai.testtools.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Bearer-token checks for both APIs (PRD 6.6).
 *
 * <p>The admin API needs the admin token, and is switched off entirely when the server
 * was started without one: it can list, delete and export recorded prompts, so "nobody
 * set a token" must not mean "anyone may". The client API needs a token only when one is
 * configured, globally or for the namespace in the path, because the expected deployment
 * is an internal test network where an open fixture store is the point.
 *
 * <p>Tokens are compared in constant time. Cheap to do, and the alternative leaks a
 * token's prefix through response timing to anyone on that network.
 *
 * @author Rifat Cakir
 */
public class AccessTokenInterceptor implements HandlerInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(AccessTokenInterceptor.class);

	private static final String BEARER = "Bearer ";

	private final CassetteServerProperties properties;

	private final JsonMapper jsonMapper;

	public AccessTokenInterceptor(CassetteServerProperties properties, JsonMapper jsonMapper) {
		Assert.notNull(properties, "properties must not be null");
		Assert.notNull(jsonMapper, "jsonMapper must not be null");
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws IOException {
		String path = request.getRequestURI().substring(request.getContextPath().length());
		if (path.startsWith("/__admin")) {
			return checkAdmin(request, response);
		}
		return checkClient(request, response);
	}

	private boolean checkAdmin(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String expected = this.properties.getAdminToken();
		if (!StringUtils.hasText(expected)) {
			return reject(response, HttpStatus.FORBIDDEN,
					"the admin API is disabled; start the server with --admin-token to enable it");
		}
		if (!matches(expected, bearerToken(request))) {
			logger.warn("CASSETTE rejected admin request {} {} from {}: missing or wrong admin token",
					request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
			return reject(response, HttpStatus.UNAUTHORIZED, "missing or wrong admin token");
		}
		return true;
	}

	private boolean checkClient(HttpServletRequest request, HttpServletResponse response) throws IOException {
		String expected = expectedClientToken(namespaceOf(request));
		if (!StringUtils.hasText(expected)) {
			return true;
		}
		if (!matches(expected, bearerToken(request))) {
			logger.warn("CASSETTE rejected client request {} {} from {}: missing or wrong client token",
					request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
			return reject(response, HttpStatus.UNAUTHORIZED, "missing or wrong client token");
		}
		return true;
	}

	private @Nullable String expectedClientToken(@Nullable String namespace) {
		if (namespace != null) {
			String specific = this.properties.getNamespaceTokens().get(namespace);
			if (StringUtils.hasText(specific)) {
				return specific;
			}
		}
		return this.properties.getClientToken();
	}

	@SuppressWarnings("unchecked")
	private static @Nullable String namespaceOf(HttpServletRequest request) {
		Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
		if (variables instanceof Map<?, ?> map) {
			Object namespace = ((Map<String, Object>) map).get("ns");
			return (namespace != null) ? namespace.toString() : null;
		}
		return null;
	}

	private static @Nullable String bearerToken(HttpServletRequest request) {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			return null;
		}
		return header.substring(BEARER.length()).trim();
	}

	private static boolean matches(String expected, @Nullable String actual) {
		if (actual == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	private boolean reject(HttpServletResponse response, HttpStatus status, String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		if (status == HttpStatus.UNAUTHORIZED) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		response.getOutputStream().write(this.jsonMapper.writeValueAsBytes(Map.of("error", message)));
		return false;
	}

}
