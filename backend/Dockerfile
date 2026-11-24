# syntax=docker/dockerfile:1

# ---- Build: compile and package the Spring Boot jar (tests run in CI/locally, not here) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
# Dependencies first, so they stay cached until pom.xml changes.
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && mv target/*.jar app.jar

# ---- Run: slim JRE, non-root ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app
COPY --from=build /build/app.jar app.jar
USER app
# Size the heap from the container's memory limit instead of the host's.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# Listens on $PORT (default 8080). All other settings come from environment variables:
# DB_URL, DB_USER, DB_PASSWORD, CORS_ALLOWED_ORIGINS, AMAZON_ALLOWED_HOSTS, SPRINGDOC_ENABLED.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
