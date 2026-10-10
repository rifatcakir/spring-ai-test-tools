# Cassette Server image: the same executable jar, unpacked into Spring Boot's layers so a
# rebuild after a code change only replaces the small application layer.
#
#   mvn -pl spring-ai-test-tools-server package -DskipTests
#   docker build -t spring-ai-test-tools-server spring-ai-test-tools-server
#   docker run -p 9090:9090 -v cassettes:/data/cassettes \
#     -e CASSETTE_ADMIN_TOKEN=change-me spring-ai-test-tools-server
#
# Configuration is environment variables (CASSETTE_STORAGE_DIR, CASSETTE_ADMIN_TOKEN,
# CASSETTE_CLIENT_TOKEN, CASSETTE_DEFAULT_MODE, SERVER_PORT) or the same --flags as
# `java -jar`, appended to `docker run`. Health: GET /actuator/health.

FROM eclipse-temurin:21-jre AS layers
WORKDIR /build
# A glob, so the Dockerfile needs no edit per version; exactly one jar must match, or a
# stale jar from an earlier version would be baked in (or break the copy) silently.
COPY target/spring-ai-test-tools-server-*.jar jars/
RUN set -e; count=$(ls jars/*.jar | wc -l);     if [ "$count" -ne 1 ]; then       echo "Expected exactly one server jar in target/, found $count:" $(ls jars) "- run mvn clean package." >&2;       exit 1;     fi;     mv jars/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
# Links the ghcr.io package to its source repository on GitHub.
LABEL org.opencontainers.image.source="https://github.com/rifatcakir/spring-ai-test-tools" \
      org.opencontainers.image.description="Cassette Server: shared fixture store for spring-ai-test-tools" \
      org.opencontainers.image.licenses="Apache-2.0"
RUN groupadd --system cassette \
    && useradd --system --gid cassette --uid 10001 --no-create-home cassette \
    && mkdir -p /data/cassettes \
    && chown cassette:cassette /data/cassettes
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER cassette
ENV CASSETTE_STORAGE_DIR=/data/cassettes
VOLUME /data/cassettes
EXPOSE 9090
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
