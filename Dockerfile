# syntax=docker/dockerfile:1

# ---- build ----
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q bootJar -x test \
 && java -Djarmode=tools -jar build/libs/*.jar extract --layers --destination /extracted

# ---- runtime ----
FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app

# Layers ordered from least to most frequently changed so image pushes/pulls only move the app layer.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

# Container-sane defaults only. GC choice and heap sizing beyond this should come from load-test data.
# Override at deploy time via JAVA_TOOL_OPTIONS in the ECS task definition.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# JDK 25 AOT cache (JEP 514/515): a training run records loaded/linked classes so new tasks start
# and reach peak speed faster -- which is what makes autoscaling under a traffic spike effective.
RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
        -Dapp.orders.consumer-enabled=false -jar app.jar \
 && chown -R app:app /app

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
