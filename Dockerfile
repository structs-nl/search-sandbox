FROM eclipse-temurin:21-alpine
RUN mkdir /opt/data /opt/app
COPY ./target/Enlight-0.1.jar /opt/app/
CMD ["java", "-Xmx8g", "-jar", "/opt/app/Enlight-0.1.jar", "-path", "/opt/data", "-serve", "8080"]