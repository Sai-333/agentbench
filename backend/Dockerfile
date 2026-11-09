# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN apk add --no-cache wget \
    && addgroup -S agentbench && adduser -S agentbench -G agentbench \
    && mkdir -p /var/agentbench/artifacts /app \
    && chown -R agentbench:agentbench /var/agentbench /app
WORKDIR /app
COPY --from=build /build/target/agentbench-*.jar /app/app.jar
USER agentbench
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
