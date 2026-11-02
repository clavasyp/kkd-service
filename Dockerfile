FROM maven:3.10.0-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
# Кэш Maven в BuildKit: правка src не идёт заново в Maven Central за зависимостями и провайдером Surefire
RUN --mount=type=cache,id=kkd-m2,target=/root/.m2,sharing=locked mvn -B -q package -DskipTests

FROM build AS test
# Повтор тестов без сброса кэша Maven: --build-arg RERUN=$(date +%s). Флаг --no-cache-filter test сбрасывает и кэш Maven
ARG RERUN
RUN --mount=type=cache,id=kkd-m2,target=/root/.m2,sharing=locked mvn -B test

FROM liquibase/liquibase:4.31.1 AS migrate
COPY db/changelog /liquibase/changelog

FROM eclipse-temurin:17.0.20.1_1-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
