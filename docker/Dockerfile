FROM eclipse-temurin:21-jre-alpine
ARG APP_NAME
RUN addgroup -S ticket && adduser -S ticket -G ticket
WORKDIR /app
COPY ${APP_NAME}/build/libs/*.jar app.jar
RUN chown ticket:ticket app.jar
ENV SPRING_PROFILES_ACTIVE=docker
USER ticket
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
