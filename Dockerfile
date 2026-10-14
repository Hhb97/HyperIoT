# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY hyperiot-server/pom.xml hyperiot-server/
COPY hyperiot-simulator/pom.xml hyperiot-simulator/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -pl hyperiot-server -am dependency:go-offline
COPY hyperiot-server hyperiot-server
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -pl hyperiot-server -am package

FROM eclipse-temurin:17-jre
RUN groupadd --system --gid 10001 hyperiot && useradd --system --uid 10001 --gid hyperiot --home-dir /data hyperiot \
    && mkdir -p /data && chown hyperiot:hyperiot /data
COPY --from=build /src/hyperiot-server/target/hyperiot-server-*.jar /app/hyperiot.jar
USER hyperiot
WORKDIR /data
VOLUME /data
# 8080 console + REST + WebSocket, 1883 MQTT (embedded broker), 9100 TCP gateway (optional)
EXPOSE 8080 1883 9100
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q UP <&3"]
ENTRYPOINT ["java", "-jar", "/app/hyperiot.jar"]
