# Build context is the repository root so the parent POM is visible.
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
COPY account-service/pom.xml account-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY gateway/src gateway/src
RUN mvn -q -pl gateway -am clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/gateway/target/gateway-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
