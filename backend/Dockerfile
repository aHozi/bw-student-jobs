# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

# ---- run ----
# Playwright's Java image ships Java, Chromium and all system libraries the headless browser needs.
FROM mcr.microsoft.com/playwright/java:v1.63.0-noble
RUN apt-get update && apt-get install -y --no-install-recommends fonts-dejavu-core && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/target/bw-student-jobs-0.1.0.jar app.jar
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    APP_STORAGE_DIR=/data/files \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
