FROM openjdk:11.0.16-jre-slim
LABEL author="TestingBot <info@testingbot.com>"

RUN apt-get update && apt-get install -y wget unzip && rm -rf /var/lib/apt/lists/*

ARG TUNNEL=testingbot-tunnel

RUN wget https://testingbot.com/downloads/${TUNNEL}.zip \
    && unzip ${TUNNEL}.zip \
    && rm ${TUNNEL}.zip

ENTRYPOINT ["java", "-jar", "testingbot-tunnel.jar"]