# Built inside the image so the result does not depend on whatever happens to be
# in build/libs on the machine running docker build.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY build.gradle settings.gradle ./

# Dependencies resolve on their own layer so editing source does not invalidate
# it.
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src src
# Tests are skipped here because they need PostgreSQL and Redis containers. CI
# runs them before this image is ever built.
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system pesabook && useradd --system --gid pesabook pesabook

COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown pesabook:pesabook app.jar

USER pesabook
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
