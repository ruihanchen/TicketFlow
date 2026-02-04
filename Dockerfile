# Stage 1: build the fat JAR
FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build

# Copy pom files first — Docker caches this layer so dependency downloads
# only re-run when pom.xml changes, not on every source code edit.
COPY pom.xml .
COPY common/pom.xml common/
COPY app/pom.xml app/

# Download dependencies into the cache layer
RUN mvn dependency:go-offline -B -q

# Now copy source and build. Only this layer invalidates on code changes.
COPY common/src common/src
COPY app/src app/src
RUN mvn package -pl app -am -DskipTests -B -q

# Stage 2: runtime image (JRE only, no Maven, no JDK, no source)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S ticketflow && adduser -S ticketflow -G ticketflow \
    && mkdir -p /app/data/debezium \
    && chown -R ticketflow:ticketflow /app

COPY --from=build --chown=ticketflow:ticketflow /build/app/target/*.jar app.jar

USER ticketflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]