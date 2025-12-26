FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/scribble-java-1.0-SNAPSHOT.jar app.jar
COPY --from=build /app/index.html .
ENV PORT=7070
EXPOSE 7070
ENTRYPOINT ["java","-jar","app.jar"]