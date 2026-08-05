# Dockerfile — Spring Boot app
#
# MULTI-STAGE BUILD:
# Stage 1 (builder): Maven + JDK — compiles the app, produces the JAR
# Stage 2 (runtime): JRE only    — runs the JAR, no build tools included
#
# Why multi-stage?
# The Maven + JDK image is ~500MB. The final runtime image (JRE only) is ~200MB.
# Build tools and source code never end up in the production image.
#
# USAGE:
# Build:  docker build -t mf-platform-app .
# Run:    docker run -p 8080:8080 mf-platform-app
# Or via: docker compose --profile fullstack up -d

# ─── Stage 1: Build ──────────────────────────────────────────────────────────
# NOTE: plain eclipse-temurin:17-jdk-alpine has no Maven binary — "mvn package"
# fails with "mvn: not found". Use an image with Maven preinstalled instead.
FROM maven:3.9-eclipse-temurin-17-alpine AS builder

WORKDIR /build

# Copy dependency descriptors first — Docker caches this layer.
# If pom.xml hasn't changed, Maven doesn't re-download dependencies on rebuild.
COPY pom.xml .
RUN mvn dependency:go-offline -B 2>/dev/null || true

# Now copy source and build
COPY src ./src
RUN mvn package -DskipTests -B

# ─── Stage 2: Runtime ────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine AS runtime

# Non-root user for security — never run production containers as root
RUN addgroup -S mfplatform && adduser -S mfplatform -G mfplatform
USER mfplatform

WORKDIR /app

# Copy only the fat JAR from the builder stage
COPY --from=builder /build/target/*.jar app.jar

# Create logs directory (AuditAspect + logback write here)
RUN mkdir -p /app/logs

# 8080 is the default Spring Boot port
EXPOSE 8080

# JVM flags:
#   -XX:+UseContainerSupport     — respect Docker CPU/memory limits (not host limits)
#   -XX:MaxRAMPercentage=75.0    — use 75% of container's RAM for the JVM heap
#   -Djava.security.egd=...      — faster startup (avoids blocking on /dev/random)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
