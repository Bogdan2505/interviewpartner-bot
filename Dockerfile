# Build
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test \
    && JAR=$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -print -quit) \
    && cp "$JAR" /workspace/app.jar

# Run
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
