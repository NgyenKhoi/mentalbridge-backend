FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src src
RUN ./mvnw -B -Dmaven.test.skip=true package

FROM build AS migration

CMD ["./mvnw", "-B", "liquibase:update"]

FROM eclipse-temurin:21-jre-alpine AS runtime

ENV SPRINGDOTENV_ENABLED=false

WORKDIR /app

RUN addgroup -S mentalbridge && adduser -S -G mentalbridge mentalbridge \
    && chown -R mentalbridge:mentalbridge /app

COPY --from=build --chown=mentalbridge:mentalbridge /workspace/target/care-service-0.0.1-SNAPSHOT.jar /app/service.jar

USER mentalbridge

EXPOSE 8081
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/app/service.jar"]
