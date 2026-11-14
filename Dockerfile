FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY --chown=10001:10001 target/autolog-server-0.1.0-SNAPSHOT.jar app.jar

USER 10001:10001
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
