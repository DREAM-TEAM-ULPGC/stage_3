# Dockerfile for Indexer Service - Stage 3 Distributed Architecture
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="DreamTeam"
LABEL description="Gutenberg Indexer Service with Hazelcast Distributed Index"

WORKDIR /app

# Install curl for healthchecks
RUN apk add --no-cache curl

# Copy the JAR file
COPY indexer-service/target/indexer-service-1.0-SNAPSHOT.jar app.jar

# Create directories for data
RUN mkdir -p datalake indexer datamart metadata

# Expose service port
EXPOSE 7002

# JVM options for containerized environment
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:7002/status || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
