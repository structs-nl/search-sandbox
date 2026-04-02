FROM eclipse-temurin:21-alpine
RUN mkdir /opt/data /opt/app
COPY ./target/Enlight-0.2.jar /opt/app/
CMD ["java", "-jar", "/opt/app/Enlight-0.2.jar", "-path", "/opt/data", "-serve", "8080"]