# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 jobforge
COPY --from=build /workspace/target/jobforge-*.jar app.jar
USER jobforge
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s \
  CMD curl -fs http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
