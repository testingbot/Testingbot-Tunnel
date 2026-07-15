# syntax=docker/dockerfile:1.7

# -------- Build stage --------
FROM maven:3.9-eclipse-temurin-11 AS build
WORKDIR /build

# Cache dependencies first
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

# Then bring sources and build the shaded JAR
COPY src ./src
RUN mvn -B -ntp -DskipTests \
        -Dgpg.skip=true \
        -Dmaven.javadoc.skip=true \
        -Dmaven.source.skip=true \
        package \
    && mv target/TestingBotTunnel-*-shaded.jar target/testingbot-tunnel.jar

# -------- Runtime stage --------
FROM eclipse-temurin:11-jre-jammy
LABEL author="TestingBot <info@testingbot.com>"

# tini for proper PID-1 signal handling
RUN apt-get update \
    && apt-get install -y --no-install-recommends tini ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Non-root user
RUN groupadd --system --gid 1001 tunnel \
    && useradd  --system --uid 1001 --gid tunnel --home-dir /home/tunnel --create-home tunnel

COPY --from=build --chown=tunnel:tunnel /build/target/testingbot-tunnel.jar /opt/testingbot/testingbot-tunnel.jar

USER tunnel
WORKDIR /home/tunnel

# Metrics / insight endpoint (default --metrics-port 8003)
EXPOSE 8003

ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/opt/testingbot/testingbot-tunnel.jar"]
