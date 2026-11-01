FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean verify

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --system campus && useradd --system --gid campus campus && chown campus:campus /app
COPY --from=build --chown=campus:campus /app/target/lost-found-0.1.0.jar app.jar
USER campus
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
