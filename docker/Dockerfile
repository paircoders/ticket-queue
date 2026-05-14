FROM eclipse-temurin:21-jre-alpine
ARG APP_NAME
WORKDIR /app
COPY ${APP_NAME}/build/libs/*.jar app.jar
ENV SPRING_PROFILES_ACTIVE=docker
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar app.jar"]
