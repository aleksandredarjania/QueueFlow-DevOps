
FROM eclipse-temurin:17-jdk AS build

WORKDIR /app

COPY src ./src

RUN mkdir -p build/classes && \
    find src/main/java -name "*.java" > sources.txt && \
    javac --release 17 -encoding UTF-8 -d build/classes @sources.txt && \
    jar --create --file build/queueflow.jar \
    --main-class queueflow.Main \
    -C build/classes . \
    -C src/main/resources public

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/build/queueflow.jar app.jar

EXPOSE 8080

CMD ["java", "-jar", "app.jar"]
