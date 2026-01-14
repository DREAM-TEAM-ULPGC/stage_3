# Dockerfile for Search Service - Stage 3 Distributed Architecture
FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="DreamTeam"
LABEL description="Gutenberg Search Service with Hazelcast Distributed Index"

WORKDIR /app

# Install curl for healthchecks
RUN apk add --no-cache curl

# Copy the JAR file
COPY search-service/target/search-service-1.0-SNAPSHOT.jar app.jar

# Create directories for data
RUN mkdir -p indexer datamart

# Expose service port
EXPOSE 7003

# JVM options for containerized environment
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=100"

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:7003/status || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
