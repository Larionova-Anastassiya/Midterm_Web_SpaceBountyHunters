FROM gradle:8.10.2-jdk17 AS build
WORKDIR /home/gradle/project
COPY --chown=gradle:gradle build.gradle.kts settings.gradle.kts ./
COPY --chown=gradle:gradle src ./src
RUN gradle installDist --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /home/gradle/project/build/install/SpaceBountyHunters/ /app/
ENV PORT=8080
EXPOSE 8080
USER 10001
CMD ["/app/bin/SpaceBountyHunters"]
