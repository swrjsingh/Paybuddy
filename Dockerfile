FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
COPY common common
COPY services services
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package
FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app
COPY --from=build /app/services/${SERVICE}/target/${SERVICE}-0.1.0.jar app.jar
RUN useradd -r -u 10001 paybuddy
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java","-jar","app.jar"]
