FROM maven:3.9.11-eclipse-temurin-21 AS compilacao
WORKDIR /projeto
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 10001 monitor && adduser -D -u 10001 -G monitor monitor \
    && mkdir /dados /app && chown monitor:monitor /dados /app
WORKDIR /app
COPY --from=compilacao /projeto/target/monitor-energia.jar /app/monitor-energia.jar
USER 10001:10001
ENV DIRETORIO_DADOS=/dados
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/monitor-energia.jar"]
