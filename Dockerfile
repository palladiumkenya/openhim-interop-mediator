FROM openjdk:8
MAINTAINER PalladiumDevs
COPY target/openhim-mediator-hl7message-handler-1.0.0-jar-with-dependencies.jar app.jar
ENTRYPOINT ["java","-jar","/app.jar"]