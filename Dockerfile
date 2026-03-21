# Build stage
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app

# Copy pom.xml
COPY pom.xml .

# Copy kiteconnect jar
COPY libs/kiteconnect.jar libs/kiteconnect.jar

# Install kiteconnect to Maven repo
RUN mvn install:install-file \
    -Dfile=libs/kiteconnect.jar \
    -DgroupId=com.zerodhatech.kiteconnect \
    -DartifactId=kiteconnect \
    -Dversion=3.5.1 \
    -Dpackaging=jar -q

# Download all dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build jar
RUN mvn clean package -DskipTests -q

# Run stage
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/trading-platform-1.0.0.jar app.jar
EXPOSE 8080
ENV JVM_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC"
ENTRYPOINT ["sh", "-c", "java $JVM_OPTS -jar app.jar"]