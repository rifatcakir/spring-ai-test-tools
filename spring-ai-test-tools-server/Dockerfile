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
COPY target/spring-ai-test-tools-server-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
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
