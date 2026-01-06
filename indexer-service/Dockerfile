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

# Copy the fat JAR
COPY --from=build /app/target/indexer-service-1.0-SNAPSHOT.jar app.jar

# Environment variables (aligned with docker-compose.yml)
ENV ACTIVEMQ_URL=tcp://activemq:61616
ENV HAZELCAST_MEMBERS=hazelcast1:5701,hazelcast2:5701,hazelcast3:5701
ENV INDEXER_ID=indexer1

ENTRYPOINT ["java", "-jar", "app.jar"]
