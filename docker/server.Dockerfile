FROM eclipse-temurin:26-jre
WORKDIR /app
ARG JAR=server/target/pathfinder-server-1.0.0.jar
COPY ${JAR} app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
