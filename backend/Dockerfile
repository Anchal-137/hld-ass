# ---- Build stage: compile + package with Maven (no local Maven required) ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Cache dependencies first (only re-downloads when pom.xml changes).
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Build the application jar.
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Run stage: slim JRE with just the jar ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/typeahead-1.0.0.jar app.jar

EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
