FROM eclipse-temurin:11-jre-jammy
LABEL author="TestingBot <info@testingbot.com>"

RUN apt-get update && apt-get install -y tini && rm -rf /var/lib/apt/lists/*

# Copy the pre-built JAR from the build context
ARG JAR_FILE=target/TestingBotTunnel-*.jar
COPY ${JAR_FILE} /testingbot-tunnel.jar

ENTRYPOINT ["/usr/bin/tini", "--", "java", "-jar", "/testingbot-tunnel.jar"]