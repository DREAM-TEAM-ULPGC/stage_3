# Build stage
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Create datalake directory
RUN mkdir -p /app/datalake

# Copy the fat JAR
COPY --from=build /app/target/crawler-service-1.0-SNAPSHOT.jar app.jar

# Environment variables (aligned with docker-compose.yml)
ENV ACTIVEMQ_URL=tcp://activemq:61616
ENV DATALAKE_PATH=/data/datalake
ENV CRAWLER_ID=crawler1
ENV REPLICATION_FACTOR=2
ENV TOTAL_NODES=3

ENTRYPOINT ["java", "-jar", "app.jar"]
