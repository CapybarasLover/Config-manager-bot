FROM maven:3.9.12-eclipse-temurin-21-alpine AS build

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS final

WORKDIR /app
RUN addgroup -g 1001 -S appuser && adduser -u 1001 -S appuser -G appuser

USER appuser

COPY --from=build app/target/Config_bot-2.0-SNAPSHOT.jar ./app.jar

EXPOSE 8080

CMD ["java", "-Dapp.env=prod", "-jar", "app.jar"]