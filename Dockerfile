FROM maven:3.10.0-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

FROM build AS test
RUN mvn -B test

FROM liquibase/liquibase:4.31.1 AS migrate
COPY db/changelog /liquibase/changelog

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
