# syntax=docker/dockerfile:1.7

# -------- Build stage --------
FROM maven:3.9-eclipse-temurin-17 AS build
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
FROM eclipse-temurin:17-jre-jammy
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

# Listeners bind 127.0.0.1 by default, which inside a container makes every published port
# unreachable from the host -- a loopback bind is per network namespace, and the container has
# its own. The namespace is the boundary here, so the listeners bind wide and the narrowing
# belongs on the host side of the port publish: `-p 127.0.0.1:4445:4445`, not `-p 4445:4445`.
# Publishing 4445 or 8087 to a routable address hands out the account's credentials and an
# open forward proxy; see the compose example.
ENV TESTINGBOT_BIND_ADDRESS=0.0.0.0

# Metrics / insight endpoint (default --metrics-port 8003)
# Also serves /healthz (process alive) and /readyz (tunnel forwarding).
EXPOSE 8003

# --ready queries /readyz and exits 0/1. The image ships no curl or wget on purpose, so the
# jar probing itself is the lightest option available here. start-period covers tunnel setup,
# which involves provisioning a remote server and normally takes well under a minute.
# If you override --metrics-port, override this HEALTHCHECK too -- it assumes the default.
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD ["java", "-jar", "/opt/testingbot/testingbot-tunnel.jar", "--ready"]

ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/opt/testingbot/testingbot-tunnel.jar"]
