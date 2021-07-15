FROM frolvlad/alpine-java:jre8-slim
LABEL author="TestingBot <info@testingbot.com>"

RUN apk add --no-cache --virtual .build-deps wget unzip

ARG TUNNEL=testingbot-tunnel

RUN wget https://testingbot.com/downloads/${TUNNEL}.zip \
    && unzip ${TUNNEL}.zip \
    && rm ${TUNNEL}.zip

ENTRYPOINT ["java", "-jar", "testingbot-tunnel/testingbot-tunnel.jar"]