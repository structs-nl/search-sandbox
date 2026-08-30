FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.source=https://github.com/structs-nl/enlight

ARG JVM_XMX=8g
RUN mkdir /opt/app
VOLUME /data

COPY ./target/enlight-0.1.jar /opt/app/
EXPOSE 8080

CMD ["java", "-Xmx${JVM_XMX}", "-jar", "/opt/app/enlight-0.1.jar", "-path", "/data", "-serve", "8080"]