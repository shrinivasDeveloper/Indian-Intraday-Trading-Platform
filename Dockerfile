FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/trading-platform-1.0.0.jar app.jar
EXPOSE 8080
ENV JVM_OPTS="-Xms512m -Xmx1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
ENTRYPOINT ["sh", "-c", "java $JVM_OPTS -jar app.jar"]
